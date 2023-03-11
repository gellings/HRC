/*
 * This software is subject to the license described in the License.txt file
 * included with this software distribution. You may not use this file except in compliance
 * with this license.
 *
 * Copyright (c) Garmin Canada Inc. 2019
 * All rights reserved.
 */

package com.dsi.ant.antplus.pluginsampler;

import static java.lang.Math.abs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import androidx.fragment.app.FragmentActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.NumberPicker;

import com.dsi.ant.plugins.antplus.pcc.AntPlusFitnessEquipmentPcc;
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc;
import com.dsi.ant.plugins.antplus.pcc.MultiDeviceSearch;
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState;
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag;
import com.dsi.ant.plugins.antplus.pcc.defines.RequestStatus;
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc;
import com.dsi.ant.plugins.antplus.pccbase.AntPlusCommonPcc;
import com.dsi.ant.plugins.antplus.pccbase.AntPlusLegacyCommonPcc;
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle;
import com.dsi.ant.plugins.pluginlib.version.PluginLibVersionInfo;
import com.dsi.ant.plugins.antplus.pcc.MultiDeviceSearch;
import com.dsi.ant.plugins.antplus.pcc.MultiDeviceSearch.RssiSupport;
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceType;
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult;
import com.dsi.ant.plugins.antplus.pccbase.MultiDeviceSearch.MultiDeviceSearchResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Dashboard 'menu' of available sampler activities
 */
public class Activity_Dashboard extends FragmentActivity
{

    public class ArrayAdapter_MultiDeviceSearchResult extends
            ArrayAdapter<MultiDeviceSearchResult>
    {
        private static final int DEFAULT_MIN_RSSI = -1;

        private ArrayList<MultiDeviceSearchResult> mData;
        private String[] mDeviceTypes;
        private int mMinRSSI = DEFAULT_MIN_RSSI;

        public ArrayAdapter_MultiDeviceSearchResult(Context context,
                                                    ArrayList<MultiDeviceSearchResult> data)
        {
            super(context, R.layout.layout_multidevice_searchresult, data);
            mData = data;
            mDeviceTypes = context.getResources().getStringArray(R.array.device_types);
        }

        /**
         * Update the display with new data for the specified position
         */
        public View getView(int position, View convertView, ViewGroup parent)
        {
            if (convertView == null)
            {
                LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
                convertView = inflater.inflate(R.layout.layout_multidevice_searchresult, null);
            }

            MultiDeviceSearchResult i = mData.get(position);

            if (i != null)
            {
                TextView tv_deviceType = (TextView) convertView
                        .findViewById(R.id.textView_multiDeviceType);
                TextView tv_deviceName = (TextView) convertView
                        .findViewById(R.id.textView_multiDeviceName);
                ProgressBar pb_RSSI = (ProgressBar) convertView
                        .findViewById(R.id.progressBar_multiDeviceRSSI);

                if (tv_deviceType != null)
                {
                    tv_deviceType.setText(mDeviceTypes[i.getAntDeviceType().ordinal()]);
                }
                if (tv_deviceName != null) {
                    tv_deviceName.setText(i.getDeviceDisplayName());
                }
            }

            return convertView;
        }
    }

    public class HR_Controller
    {
        private double m_target_power;
        private double m_max_hr;
        private double m_power_offset;
        private long m_last_update_ms;
        boolean m_next_tick = false;

        HR_Controller()
        {
            m_target_power = 200.0;
            m_max_hr = 165;
            m_power_offset = 0;
        }
        void set_max_hr(double max_hr) { m_max_hr = max_hr; }
        void set_target_power(double target_power)
        {
            m_next_tick = true;
            m_target_power = target_power;
        }

        boolean Tick(double curr_hr)
        {
            if (m_next_tick)
            {
                m_next_tick = false;
                return true;
            }
            long curr_time_ms = System.currentTimeMillis();
            double dt = (curr_time_ms - m_last_update_ms)/1000.0;
            if (dt > 1.0) dt = 1.0;
            if (dt < 0.45) return false;
            m_last_update_ms = curr_time_ms;

            if (curr_hr > m_max_hr || abs(m_power_offset) > 2) {
                m_power_offset += (curr_hr - m_max_hr) * dt * (1.0 / 10);
                return true;
            }
            return false;
        }

        double get_power_command() { return m_target_power - m_power_offset; }
    };

    HR_Controller mController;

    TextView trainerDeviceView;
    TextView hrDeviceView;
    TextView powerView;
    TextView heartRateView;

    protected ListAdapter mAdapter;
    protected ListView mList;

    Button mSearchButton;
    MultiDeviceSearch mSearch;

    ListView mFoundDevicesList;
    ArrayList<MultiDeviceSearchResult> mFoundDevices = new ArrayList<>();
    ArrayAdapter_MultiDeviceSearchResult mFoundAdapter;
    ListView mConnectedDevicesList;
    ArrayList<MultiDeviceSearchResult> mConnectedDevices = new ArrayList<>();
    ArrayAdapter_MultiDeviceSearchResult mConnectedAdapter;
    Context mContext;

    AntPlusHeartRatePcc hrPcc = null;
    protected PccReleaseHandle<AntPlusHeartRatePcc> hrReleaseHandle = null;
    double mCurrHr;
    AntPlusFitnessEquipmentPcc fePcc = null;
    PccReleaseHandle<AntPlusFitnessEquipmentPcc> feReleaseHandle = null;
    Boolean ERG_capabile = false;
    Boolean SIM_capabile = false;
    Boolean FE_subscriptions_done = false;

    Timer mControlTimer = new Timer();
    Boolean mPowRequestFinished = true;

    //Initialize the list
    @SuppressWarnings("serial") //Suppress warnings about hash maps not having custom UIDs
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        try
        {
            Log.i("ANT+ Plugin Sampler", "Version: " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (NameNotFoundException e)
        {
            Log.i("ANT+ Plugin Sampler", "Version: " + e.toString());
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mController = new HR_Controller();

        NumberPicker pp = findViewById(R.id.powerPicker);
        pp.setMaxValue(300);
        pp.setMinValue(50);
        pp.setValue(150);
        mController.set_target_power(150.0);
        pp.setOnValueChangedListener(onPowerChangeListener);

        NumberPicker hrp = findViewById(R.id.hrPicker);
        hrp.setMaxValue(200);
        hrp.setMinValue(50);
        hrp.setValue(150);
        mController.set_max_hr(150.0);
        hrp.setOnValueChangedListener(onHRChangeListener);

        trainerDeviceView = (TextView)findViewById(R.id.trainer_device);
        hrDeviceView = (TextView)findViewById(R.id.hr_device);
        powerView =(TextView)findViewById(R.id.powerValueView);
        heartRateView =(TextView)findViewById(R.id.heartRateValueView);

        mSearchButton = (Button) findViewById(R.id.button_StartMultiDeviceSearch);
        mContext = getApplicationContext();
        mSearchButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                EnumSet<DeviceType> devices = EnumSet.of(DeviceType.HEARTRATE, DeviceType.FITNESS_EQUIPMENT);

                // start the multi-device search
                mSearch = new MultiDeviceSearch(mContext, devices, mCallback);
            }
        });
        mFoundDevicesList = (ListView) findViewById(R.id.listView_FoundDevices);

        mFoundAdapter = new ArrayAdapter_MultiDeviceSearchResult(this, mFoundDevices);
        mFoundDevicesList.setAdapter(mFoundAdapter);

        mFoundDevicesList.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id)
            {
                launchConnection(mFoundAdapter.getItem(position));
            }
        });

        mConnectedDevicesList = (ListView) findViewById(R.id.listView_AlreadyConnectedDevices);

        mConnectedAdapter = new ArrayAdapter_MultiDeviceSearchResult(this, mConnectedDevices);
        mConnectedDevicesList.setAdapter(mConnectedAdapter);

        mConnectedDevicesList.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id)
            {
                launchConnection(mConnectedAdapter.getItem(position));
            }
        });

        try
        {
            ((TextView)findViewById(R.id.textView_PluginSamplerVersion)).setText("App Version: " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (NameNotFoundException e)
        {
            ((TextView)findViewById(R.id.textView_PluginSamplerVersion)).setText("App Version: ERR");
        }
        ((TextView)findViewById(R.id.textView_PluginLibVersion)).setText("Built w/ PluginLib: " + PluginLibVersionInfo.PLUGINLIB_VERSION_STRING);
        ((TextView)findViewById(R.id.textView_PluginsPkgVersion)).setText("Installed Plugin Version: " + AntPluginPcc.getInstalledPluginsVersionString(this));
    }

    NumberPicker.OnValueChangeListener onPowerChangeListener =
        new NumberPicker.OnValueChangeListener(){
            @Override
            public void onValueChange(NumberPicker numberPicker, int i, int i1) {
                mController.set_target_power(numberPicker.getValue());
                Toast.makeText(Activity_Dashboard.this,
                        "trainer power target set to "+numberPicker.getValue(), Toast.LENGTH_SHORT);


//                // how to send track resistance
//                BigDecimal grade = new BigDecimal("0.1");
//                BigDecimal rollingResistanceCoefficient = new BigDecimal("0.001");
//                boolean submitted = fePcc.getTrainerMethods().requestSetTrackResistance(grade, rollingResistanceCoefficient, requestFinishedReceiver);
//                if(!submitted)
//                    Toast.makeText(mContext, "Request Could not be Made", Toast.LENGTH_SHORT).show();
//
//                // how to send target power
//                BigDecimal targetPower = new BigDecimal("42.25");   //42.25%
//                boolean submitted = fePcc.getTrainerMethods().requestSetTargetPower(targetPower, requestFinishedReceiver);
//                if(!submitted)
//                    Toast.makeText(mContext, "Request Could not be Made", Toast.LENGTH_SHORT).show();
//
//                // how to send user configuration
//                AntPlusFitnessEquipmentPcc.UserConfiguration config = new AntPlusFitnessEquipmentPcc.UserConfiguration();
//                config.bicycleWeight = new BigDecimal("10.00");         //10kg bike weight
//                config.gearRatio = new BigDecimal("0.03");              //0.03 gear ratio
//                config.bicycleWheelDiameter = new BigDecimal("0.70");   //0.70m wheel diameter
//                config.userWeight = new BigDecimal("75.00");            //75kg user
//                boolean submitted = fePcc.requestSetUserConfiguration(config, requestFinishedReceiver);
//                if(!submitted)
//                    Toast.makeText(mContext, "Request Could not be Made", Toast.LENGTH_SHORT).show();
            }
        };

    NumberPicker.OnValueChangeListener onHRChangeListener =
        new NumberPicker.OnValueChangeListener(){
        @Override
        public void onValueChange(NumberPicker numberPicker, int i, int i1) {
            mController.set_max_hr(numberPicker.getValue());
            heartRateView.setText(String.valueOf(numberPicker.getValue()));
            Toast.makeText(Activity_Dashboard.this,
                    "max heart rate set to "+numberPicker.getValue(), Toast.LENGTH_SHORT);
        }
    };



    /**
     * Callbacks from the multi-device search interface
     */
    private MultiDeviceSearch.SearchCallbacks mCallback = new MultiDeviceSearch.SearchCallbacks() {
        public void onDeviceFound(final MultiDeviceSearchResult deviceFound) {
            final MultiDeviceSearchResult result = deviceFound;

            if (deviceFound.isAlreadyConnected())
            {
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        // connected device category is invisible unless there
                        // are some present
                        if (mConnectedAdapter.isEmpty())
                        {
                            mConnectedDevicesList.setVisibility(View.VISIBLE);
                        }

                        mConnectedAdapter.add(result);
                        mConnectedAdapter.notifyDataSetChanged();
                    }
                });
            }
            else
            {
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        mFoundAdapter.add(result);
                        mFoundAdapter.notifyDataSetChanged();
                    }
                });
            }
        }

        public void onSearchStopped(RequestAccessResult reason)
        {
            Intent result = new Intent();
            result.putExtra("com.dsi.ant.antplus.pluginsampler.multidevicesearch.result", reason.getIntValue());
            setResult(1, result);
            Toast.makeText(mContext, "End device search.", Toast.LENGTH_SHORT).show();
            finish();
            mSearch.close();
            mSearch = null;
        }

        @Override
        public void onSearchStarted(RssiSupport supportsRssi) {
            Toast.makeText(mContext, "Start device search.", Toast.LENGTH_SHORT).show();
        }
    };

    public void subscribeToHrEvents()
    {
        hrPcc.subscribeHeartRateDataEvent(new AntPlusHeartRatePcc.IHeartRateDataReceiver()
        {
            @Override
            public void onNewHeartRateData(final long estTimestamp, EnumSet<EventFlag> eventFlags,
                                           final int computedHeartRate, final long heartBeatCount,
                                           final BigDecimal heartBeatEventTime, final AntPlusHeartRatePcc.DataState dataState)
            {
                // Mark heart rate with asterisk if zero detected
                final String textHeartRate = String.valueOf(computedHeartRate)
                        + ((AntPlusHeartRatePcc.DataState.ZERO_DETECTED.equals(dataState)) ? "*" : "");

                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        mCurrHr = computedHeartRate;
                        heartRateView.setText(textHeartRate);
                    }
                });
            }
        });
    }

    AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc> hr_IPluginAccessResultReceiver =
        new AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc>()
        {
            //Handle the result, connecting to events on success or reporting failure to user.
            @Override
            public void onResultReceived(AntPlusHeartRatePcc result, RequestAccessResult resultCode,
                                         DeviceState initialDeviceState)
            {
                switch(resultCode)
                {
                    case SUCCESS:
                        hrPcc = result;
                        subscribeToHrEvents();
                        break;
                    case CHANNEL_NOT_AVAILABLE:
                        Toast.makeText(mContext, "Channel Not Available", Toast.LENGTH_SHORT).show();
                        break;
                    case ADAPTER_NOT_DETECTED:
                        Toast.makeText(mContext, "ANT Adapter Not Available. Built-in ANT hardware or external adapter required.", Toast.LENGTH_SHORT).show();
                        break;
                    case BAD_PARAMS:
                        //Note: Since we compose all the params ourself, we should never see this result
                        Toast.makeText(mContext, "Bad request parameters.", Toast.LENGTH_SHORT).show();
                        break;
                    case OTHER_FAILURE:
                        Toast.makeText(mContext, "RequestAccess failed. See logcat for details.", Toast.LENGTH_SHORT).show();
                        break;
                    case DEPENDENCY_NOT_INSTALLED:
                        Toast.makeText(mContext, "Missing dependancy", Toast.LENGTH_SHORT).show();
                        break;
                    case USER_CANCELLED:
                        break;
                    case UNRECOGNIZED:
                        Toast.makeText(mContext,
                                "Failed: UNRECOGNIZED. PluginLib Upgrade Required?",
                                Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        Toast.makeText(mContext, "Unrecognized result: " + resultCode, Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        };

    final AntPluginPcc.IPluginAccessResultReceiver<AntPlusFitnessEquipmentPcc> fe_IPluginAccessResultReceiver =
        new AntPluginPcc.IPluginAccessResultReceiver<AntPlusFitnessEquipmentPcc>()
        {
            //Handle the result, connecting to events on success or reporting failure to user.
            @Override
            public void onResultReceived(AntPlusFitnessEquipmentPcc result,
                                         RequestAccessResult resultCode, DeviceState initialDeviceState)
            {
                switch(resultCode)
                {
                    case SUCCESS:
                        fePcc = result;
                        subscribeToEvents();
                        break;
                    case CHANNEL_NOT_AVAILABLE:
                        Toast.makeText(mContext, "Channel Not Available", Toast.LENGTH_SHORT).show();
                        break;
                    case ADAPTER_NOT_DETECTED:
                        Toast.makeText(mContext, "ANT Adapter Not Available. Built-in ANT hardware or external adapter required.", Toast.LENGTH_SHORT).show();
                        break;
                    case BAD_PARAMS:
                        //Note: Since we compose all the params ourself, we should never see this result
                        Toast.makeText(mContext, "Bad request parameters.", Toast.LENGTH_SHORT).show();
                        break;
                    case OTHER_FAILURE:
                        Toast.makeText(mContext, "RequestAccess failed. See logcat for details.", Toast.LENGTH_SHORT).show();
                        break;
                    case DEPENDENCY_NOT_INSTALLED:
                        Toast.makeText(mContext, "Missing dependency", Toast.LENGTH_SHORT).show();
                        break;
                    case USER_CANCELLED:
                        break;
                    case UNRECOGNIZED:
                        Toast.makeText(mContext, "Failed: UNRECOGNIZED. PluginLib Upgrade Required?", Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        Toast.makeText(mContext, "Unrecognized result: " + resultCode, Toast.LENGTH_SHORT).show();
                        break;
                }
            }

            private void subscribeToEvents()
            {
                fePcc.subscribeGeneralFitnessEquipmentDataEvent(new AntPlusFitnessEquipmentPcc.IGeneralFitnessEquipmentDataReceiver()
                {
                    @Override
                    public void onNewGeneralFitnessEquipmentData(final long estTimestamp,
                                                                 EnumSet<EventFlag> eventFlags, final BigDecimal elapsedTime,
                                                                 final long cumulativeDistance, final BigDecimal instantaneousSpeed,
                                                                 final boolean virtualInstantaneousSpeed, final int instantaneousHeartRate,
                                                                 final AntPlusFitnessEquipmentPcc.HeartRateDataSource heartRateDataSource)
                    {
                        runOnUiThread(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                ;
                            }
                        });
                    }
                });
            }
        };

    AntPlusFitnessEquipmentPcc.IFitnessEquipmentStateReceiver mFitnessEquipmentStateReceiver =
        new AntPlusFitnessEquipmentPcc.IFitnessEquipmentStateReceiver()
        {
            @Override
            public void onNewFitnessEquipmentState(final long estTimestamp,
                                                   EnumSet<EventFlag> eventFlags, final AntPlusFitnessEquipmentPcc.EquipmentType equipmentType,
                                                   final AntPlusFitnessEquipmentPcc.EquipmentState equipmentState)
            {
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (equipmentType == AntPlusFitnessEquipmentPcc.EquipmentType.TRAINER)
                        {
                            if (!FE_subscriptions_done) {
                                fePcc.getTrainerMethods().subscribeRawTrainerDataEvent(new AntPlusFitnessEquipmentPcc.IRawTrainerDataReceiver()
                                {
                                    @Override
                                    public void onNewRawTrainerData(final long estTimestamp, EnumSet<EventFlag> eventFlags,
                                                                    final long updateEventCount, final int instantaneousCadence, final int instantaneousPower,
                                                                    final long accumulatedPower)
                                    {
                                        runOnUiThread(new Runnable()
                                        {
                                            @Override
                                            public void run()
                                            {
                                                powerView.setText(String.valueOf(instantaneousPower));
                                            }
                                        });
                                    }
                                });
                                fePcc.subscribeCapabilitiesEvent(new AntPlusFitnessEquipmentPcc.ICapabilitiesReceiver() {
                                    @Override
                                    public void onNewCapabilities(final long estTimestamp, EnumSet<EventFlag> eventFlags,
                                                                  final AntPlusFitnessEquipmentPcc.Capabilities capabilities) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                ERG_capabile = capabilities.targetPowerModeSupport;
                                                SIM_capabile = capabilities.simulationModeSupport;
                                            }
                                        });
                                    }
                                });

                                fePcc.getTrainerMethods().subscribeTargetPowerEvent(new AntPlusFitnessEquipmentPcc.ITargetPowerReceiver() {
                                    @Override
                                    public void onNewTargetPower(final long estTimestamp, EnumSet<EventFlag> eventFlags,
                                                                 final BigDecimal targetPower) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                powerView.setText(targetPower.toString());
                                            }
                                        });
                                    }
                                });

                                BigDecimal targetPower = new BigDecimal(mController.get_power_command());
                                mPowRequestFinished = false;
                                boolean submitted = fePcc.getTrainerMethods().requestSetTargetPower(targetPower, requestFinishedReceiver);
                                if(!submitted)
                                    Toast.makeText(mContext, "Request Could not be Made", Toast.LENGTH_SHORT).show();

                                mControlTimer.schedule(new TimerTask() {
                                    @Override
                                    public void run() {
                                        if (mController.Tick(mCurrHr) && mPowRequestFinished)
                                        {
                                            BigDecimal targetPower = new BigDecimal(mController.get_power_command());
                                            mPowRequestFinished = false;
                                            boolean submitted = fePcc.getTrainerMethods().requestSetTargetPower(targetPower, requestFinishedReceiver);
                                            if(!submitted)
                                                Toast.makeText(mContext, "Request Could not be Made", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                }, 500, 500);
                            }

                            FE_subscriptions_done = true;
                        }

                    }
                });
            }

        };

    AntPluginPcc.IDeviceStateChangeReceiver base_IDeviceStateChangeReceiver =
    new AntPluginPcc.IDeviceStateChangeReceiver()
    {
        @Override
        public void onDeviceStateChange(final DeviceState newDeviceState)
        {
            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
//                    tv_status.setText(hrPcc.getDeviceName() + ": " + newDeviceState);
                }
            });
        }
    };

    public void launchConnection(MultiDeviceSearchResult result)
    {
        if (result.getAntDeviceType() == DeviceType.FITNESS_EQUIPMENT)
        {
            Toast.makeText(this, "Connect FE device " + String.valueOf(result.getAntDeviceNumber()), Toast.LENGTH_SHORT).show();
            feReleaseHandle = AntPlusFitnessEquipmentPcc.requestNewOpenAccess(this, result.getAntDeviceNumber(), 0,
                    fe_IPluginAccessResultReceiver, base_IDeviceStateChangeReceiver, mFitnessEquipmentStateReceiver);
            trainerDeviceView.setText(String.valueOf(result.getAntDeviceNumber()));
        }
        if (result.getAntDeviceType() == DeviceType.HEARTRATE)
        {
            Toast.makeText(this, "Connect HR device " + String.valueOf(result.getAntDeviceNumber()), Toast.LENGTH_SHORT).show();
            hrReleaseHandle = AntPlusHeartRatePcc.requestAccess(this, result.getAntDeviceNumber(), 0,
                    hr_IPluginAccessResultReceiver, base_IDeviceStateChangeReceiver);
            hrDeviceView.setText(String.valueOf(result.getAntDeviceNumber()));
        }
    }


    final AntPlusCommonPcc.IRequestFinishedReceiver requestFinishedReceiver = new AntPlusCommonPcc.IRequestFinishedReceiver()
    {
        @Override
        public void onNewRequestFinished(final RequestStatus requestStatus)
        {
            runOnUiThread(
                    new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            mPowRequestFinished = true;
                            switch(requestStatus)
                            {
                                case SUCCESS:
//                                    Toast.makeText(mContext, "Request Successfully Sent", Toast.LENGTH_SHORT).show();
                                    break;
                                case FAIL_PLUGINS_SERVICE_VERSION:
                                    Toast.makeText(mContext,
                                            "Plugin Service Upgrade Required?",
                                            Toast.LENGTH_SHORT).show();
                                    break;
                                default:
                                    Toast.makeText(mContext, "Request Failed to be Sent", Toast.LENGTH_SHORT).show();
                                    break;
                            }
                        }
                    });
        }
    };
}
