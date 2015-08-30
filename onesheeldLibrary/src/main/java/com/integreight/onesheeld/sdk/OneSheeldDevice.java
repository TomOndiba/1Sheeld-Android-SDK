package com.integreight.onesheeld.sdk;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Represents a hardware 1Sheeld board.
 * <p>It is responsible for all operations and communications with the board.</p>
 * @see OneSheeldManager
 */
public class OneSheeldDevice {
    /**
     * The input pin mode.
     */
    public static final byte INPUT = 0;
    /**
     * The output pin mode.
     */
    public static final byte OUTPUT = 1;
    /**
     * The constant PWM.
     */
    public static final byte PWM = 3;
    /**
     * The constant LOW.
     */
    public static final boolean LOW = false;
    /**
     * The constant HIGH.
     */
    public static final boolean HIGH = true;
    /**
     * The constant A0.
     */
    public static final int A0 = 14;
    /**
     * The constant A1.
     */
    public static final int A1 = 15;
    /**
     * The constant A2.
     */
    public static final int A2 = 16;
    /**
     * The constant A3.
     */
    public static final int A3 = 17;
    /**
     * The constant A4.
     */
    public static final int A4 = 18;
    /**
     * The constant A5.
     */
    public static final int A5 = 19;
    private final char MAX_DATA_BYTES = 4096;
    private final char MAX_OUTPUT_BYTES = 32;
    private final byte DIGITAL_MESSAGE = (byte) 0x90;
    private final byte ANALOG_MESSAGE = (byte) 0xE0;
    private final byte REPORT_DIGITAL = (byte) 0xD0;
    private final byte SET_PIN_MODE = (byte) 0xF4;
    private final byte REPORT_VERSION = (byte) 0xF9;
    private final byte START_SYSEX = (byte) 0xF0;
    private final byte END_SYSEX = (byte) 0xF7;
    private final byte REPORT_INPUT_PINS = (byte) 0x5F;
    private final byte BLUETOOTH_RESET = (byte) 0x61;
    private final byte IS_ALIVE = (byte) 0x62;
    private final byte MUTE_FIRMATA = (byte) 0x64;
    private final byte SERIAL_DATA = (byte) 0x66;
    private final byte CONFIGURATION_SHIELD_ID = (byte) 0x00;
    private final byte BT_CONNECTED = (byte) 0x01;
    private final byte QUERY_LIBRARY_VERSION = (byte) 0x03;
    private final byte LIBRARY_VERSION_RESPONSE = (byte) 0x01;
    private final byte IS_HARDWARE_CONNECTED_QUERY = (byte) 0x02;
    private final byte IS_CALLBACK_ENTERED = (byte) 0x03;
    private final byte IS_CALLBACK_EXITED = (byte) 0x04;
    private final Object sendingDataLock = new Object();
    private final Object arduinoCallbacksLock = new Object();
    private final Object isConnectedLock = new Object();
    private final int MAX_BUFFER_SIZE = 1024;
    private Queue<ShieldFrame> queuedFrames;
    private LinkedBlockingQueue<Byte> bluetoothBuffer;
    private LinkedBlockingQueue<Byte> serialBuffer;
    private BluetoothBufferListeningThread bluetoothBufferListeningThread;
    private SerialBufferListeningThread serialBufferListeningThread;
    private String name;
    private String address;
    private boolean isPaired;
    private BluetoothDevice bluetoothDevice;
    private ConnectedThread connectedThread;
    private boolean isBluetoothBufferWaiting;
    private boolean isSerialBufferWaiting;
    private TimeOut ShieldFrameTimeout;
    private boolean isConnected;
    private OneSheeldManager manager;
    private CopyOnWriteArrayList<OneSheeldConnectionCallback> connectionCallbacks;
    private CopyOnWriteArrayList<OneSheeldErrorCallback> errorCallbacks;
    private CopyOnWriteArrayList<OneSheeldDataCallback> dataCallbacks;
    private CopyOnWriteArrayList<OneSheeldVersionQueryCallback> versionQueryCallbacks;
    private int arduinoLibraryVersion;
    private Thread exitingCallbacksThread, enteringCallbacksThread;
    private TimeOut callbacksTimeout;
    private long lastTimeCallbacksExited;
    private int waitForData;
    private byte executeMultiByteCommand;
    private byte multiByteChannel;
    private byte[] storedInputData;
    private boolean parsingSysex;
    private int sysexBytesRead;
    private int majorVersion;
    private int minorVersion;
    private boolean isFirmwareVersionQueried;
    private boolean isLibraryVersionQueried;
    private boolean isInACallback;
    private boolean isMuted;
    private int[] digitalOutputData = {0, 0, 0};
    private int[] digitalInputData = {0, 0, 0};

    /**
     * Instantiates a new One sheeld device.
     *
     * @param address the address
     */
    public OneSheeldDevice(String address) {
        checkBluetoothAddress(address);
        this.name = address;
        this.address = address;
        this.isPaired = false;
        initialize();
    }

    /**
     * Instantiates a new One sheeld device.
     *
     * @param address the address
     * @param name the name
     */
    public OneSheeldDevice(String address, String name) {
        checkBluetoothAddress(address);
        this.name = name;
        this.address = address;
        isPaired = false;
        initialize();
    }

    /**
     * Instantiates a new One sheeld device.
     *
     * @param address the address
     * @param name the name
     * @param isPaired the is paired
     */
    OneSheeldDevice(String address, String name, boolean isPaired) {
        checkBluetoothAddress(address);
        this.name = name;
        this.address = address;
        this.isPaired = isPaired;
        initialize();
    }

    private void initialize() {
        bluetoothDevice = BluetoothUtils.getBluetoothAdapter().getRemoteDevice(address);
        isConnected = false;
        bluetoothBuffer = new LinkedBlockingQueue<>();
        serialBuffer = new LinkedBlockingQueue<>();
        manager = OneSheeldManager.getInstance();
        connectionCallbacks = new CopyOnWriteArrayList<>();
        errorCallbacks = new CopyOnWriteArrayList<>();
        dataCallbacks = new CopyOnWriteArrayList<>();
        versionQueryCallbacks = new CopyOnWriteArrayList<>();
        queuedFrames = new ConcurrentLinkedQueue<>();
        isMuted = false;
        arduinoLibraryVersion = 0;
        majorVersion = 0;
        minorVersion = 0;
        isFirmwareVersionQueried = false;
        isLibraryVersionQueried = false;
        sysexBytesRead = 0;
        parsingSysex = false;
        waitForData = 0;
        executeMultiByteCommand = 0;
        multiByteChannel = 0;
        storedInputData = new byte[MAX_DATA_BYTES];
    }

    private void stopBuffersThreads() {
        if (serialBufferListeningThread != null) {
            serialBufferListeningThread.stopRunning();
        }
        if (bluetoothBufferListeningThread != null) {
            bluetoothBufferListeningThread.stopRunning();
        }
    }

    /**
     * Add connection callback.
     *
     * @param connectionCallback the connection callback
     */
    public void addConnectionCallback(OneSheeldConnectionCallback connectionCallback) {
        if (connectionCallback != null && !connectionCallbacks.contains(connectionCallback))
            connectionCallbacks.add(connectionCallback);
    }

    /**
     * Add data callback.
     *
     * @param dataCallback the data callback
     */
    public void addDataCallback(OneSheeldDataCallback dataCallback) {
        if (dataCallback != null && !dataCallbacks.contains(dataCallback))
            dataCallbacks.add(dataCallback);
    }

    /**
     * Add version query callback.
     *
     * @param versionQueryCallback the version query callback
     */
    public void addVersionQueryCallback(OneSheeldVersionQueryCallback versionQueryCallback) {
        if (versionQueryCallback != null && !versionQueryCallbacks.contains(versionQueryCallback))
            versionQueryCallbacks.add(versionQueryCallback);
    }

    /**
     * Add error callback.
     *
     * @param errorCallback the error callback
     */
    public void addErrorCallback(OneSheeldErrorCallback errorCallback) {
        if (errorCallback != null && !errorCallbacks.contains(errorCallback))
            errorCallbacks.add(errorCallback);
    }

    /**
     * Remove connection callback.
     *
     * @param connectionCallback the connection callback
     */
    public void removeConnectionCallback(OneSheeldConnectionCallback connectionCallback) {
        if (connectionCallback != null && connectionCallbacks.contains(connectionCallback))
            connectionCallbacks.remove(connectionCallback);
    }

    /**
     * Remove error callback.
     *
     * @param errorCallback the error callback
     */
    public void removeErrorCallback(OneSheeldErrorCallback errorCallback) {
        if (errorCallback != null && errorCallbacks.contains(errorCallback))
            errorCallbacks.remove(errorCallback);
    }

    /**
     * Remove data callback.
     *
     * @param dataCallback the data callback
     */
    public void removeDataCallback(OneSheeldDataCallback dataCallback) {
        if (dataCallback != null && dataCallbacks.contains(dataCallback))
            dataCallbacks.remove(dataCallback);
    }

    /**
     * Remove version query callback.
     *
     * @param versionQueryCallback the version query callback
     */
    public void removeVersionQueryCallback(OneSheeldVersionQueryCallback versionQueryCallback) {
        if (versionQueryCallback != null && versionQueryCallbacks.contains(versionQueryCallback))
            versionQueryCallbacks.remove(versionQueryCallback);
    }

    /**
     * Add callbacks.
     *
     * @param connectionCallback the connection callback
     * @param dataCallback the data callback
     * @param versionQueryCallback the version query callback
     * @param errorCallback the error callback
     */
    public void addCallbacks(OneSheeldConnectionCallback connectionCallback, OneSheeldDataCallback dataCallback, OneSheeldVersionQueryCallback versionQueryCallback, OneSheeldErrorCallback errorCallback) {
        addConnectionCallback(connectionCallback);
        addErrorCallback(errorCallback);
        addDataCallback(dataCallback);
        addVersionQueryCallback(versionQueryCallback);
    }

    private void clearAllBuffers() {
        bluetoothBuffer.clear();
        serialBuffer.clear();
    }

    private byte readByteFromSerialBuffer() throws InterruptedException,
            ShieldFrameNotComplete {
        if (ShieldFrameTimeout != null && ShieldFrameTimeout.isTimeout())
            throw new ShieldFrameNotComplete();
        isSerialBufferWaiting = true;
        byte temp = serialBuffer.take();
        if (ShieldFrameTimeout != null)
            ShieldFrameTimeout.resetTimer();
        return temp;
    }

    private int readByteFromBluetoothBuffer() throws InterruptedException {
        isBluetoothBufferWaiting = true;
        return bluetoothBuffer.take();
    }

    private void checkBluetoothAddress(String address) {
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new InvalidBluetoothAddressException("Bluetooth address is invalid, are you sure you specified it correctly?");
        }
    }

    /**
     * Gets bluetooth device.
     *
     * @return the bluetooth device
     */
    BluetoothDevice getBluetoothDevice() {
        return bluetoothDevice;
    }

    /**
     * Gets name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets name.
     *
     * @param name the name
     */
    void setName(String name) {
        this.name = name;
    }

    /**
     * Gets address.
     *
     * @return the address
     */
    public String getAddress() {
        return address;
    }

    /**
     * Is paired.
     *
     * @return the boolean
     */
    public boolean isPaired() {
        return isPaired;
    }

    /**
     * Sets paired.
     *
     * @param isPaired the is paired
     */
    void setPaired(boolean isPaired) {
        this.isPaired = isPaired;
    }

    /**
     * Connect using.
     *
     * @param socket the socket
     */
    synchronized void connectUsing(BluetoothSocket socket) {
        try {
            closeConnection();
            connectedThread = new ConnectedThread(socket);
            connectedThread.start();
        } catch (IllegalArgumentException e) {
            return;
        }
    }

    private void onConnect() {
        manager.onConnect(this);
        for (OneSheeldConnectionCallback connectionCallback : connectionCallbacks) {
            connectionCallback.onConnect(this);
        }
    }

    private void onDisconnect() {
        manager.onDisconnect(this);
        for (OneSheeldConnectionCallback connectionCallback : connectionCallbacks) {
            connectionCallback.onDisconnect(this);
        }
    }

    /**
     * On error.
     *
     * @param error the error
     */
    void onError(OneSheeldError error) {
        for (OneSheeldErrorCallback errorCallback : errorCallbacks) {
            errorCallback.onError(this, error);
        }
    }

    /**
     * On connection retry.
     *
     * @param retryCount the retry count
     */
    void onConnectionRetry(int retryCount) {
        for (OneSheeldConnectionCallback connectionCallback : connectionCallbacks) {
            connectionCallback.onConnectionRetry(this, retryCount);
        }
    }

    private void resetProcessInput() {
        waitForData = 0;
        executeMultiByteCommand = 0;
        multiByteChannel = 0;
        storedInputData = new byte[MAX_DATA_BYTES];
        parsingSysex = false;
        sysexBytesRead = 0;
    }

    private byte[] getByteAs2SevenBitsBytes(byte data) {
        byte[] temp = new byte[2];
        temp[0] = (byte) ((data & 0xFF) & 127);
        temp[1] = (byte) (((data & 0xFF) >> 7) & 127);
        return temp;
    }

    private byte[] getByteArrayAs2SevenBitsBytesArray(byte[] data) {
        byte[] temp = new byte[data.length * 2];
        for (int i = 0; i < temp.length; i += 2) {
            temp[i] = getByteAs2SevenBitsBytes(data[i / 2])[0];
            temp[i + 1] = getByteAs2SevenBitsBytes(data[i / 2])[1];
        }
        return temp;
    }

    private void sysex(byte command, byte[] bytes) {
        byte[] data = getByteArrayAs2SevenBitsBytesArray(bytes);
        if (data.length > 32)
            return;
        byte[] writeData = new byte[data.length + 3];
        writeData[0] = START_SYSEX;
        writeData[1] = command;
        for (int i = 0; i < data.length; i++) {
            writeData[i + 2] = (byte) (data[i] & 127); // 7bit
        }
        writeData[writeData.length - 1] = END_SYSEX;
        write(writeData);
    }

    private void notifyHardwareOfConnection() {
        Log.d("Device " + this.name + ": Notifying the board with connection.");
        sendShieldFrame(new ShieldFrame(CONFIGURATION_SHIELD_ID, BT_CONNECTED));
    }


    /**
     * Queue shield frame.
     *
     * @param frame the frame
     */
    public void queueShieldFrame(ShieldFrame frame) {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        queuedFrames.add(frame);
        callbackEntered();
    }

    private void callbackEntered() {
        if (enteringCallbacksThread != null && enteringCallbacksThread.isAlive())
            return;
        enteringCallbacksThread = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (arduinoCallbacksLock) {
                    isInACallback = true;
                }
                if (callbacksTimeout == null || !callbacksTimeout.isAlive()) {
                    callbacksTimeout = new TimeOut(5000, 1000, new TimeOut.TimeOutCallback() {
                        @Override
                        public void onTimeOut() {
                            callbackExited();
                        }

                        @Override
                        public void onTick(long milliSecondsLeft) {

                        }
                    });
                } else
                    callbacksTimeout.resetTimer();
            }
        });
        enteringCallbacksThread.start();
    }

    private void callbackExited() {
        synchronized (arduinoCallbacksLock) {
            isInACallback = false;
            lastTimeCallbacksExited = SystemClock.elapsedRealtime();
        }
        if (callbacksTimeout != null && !callbacksTimeout.isAlive()) callbacksTimeout.stopTimer();
        if (exitingCallbacksThread != null && exitingCallbacksThread.isAlive())
            return;
        exitingCallbacksThread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean sent;
                while (!queuedFrames.isEmpty()) {
                    sent = false;
                    synchronized (arduinoCallbacksLock) {
                        if (!isInACallback && lastTimeCallbacksExited != 0 && (SystemClock.elapsedRealtime() - lastTimeCallbacksExited > 200)) {
                            sendFrame(queuedFrames.poll());
                            sent = true;
                        }
                    }
                    if (sent) try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        });
        exitingCallbacksThread.start();
    }

    /**
     * Send shield frame.
     *
     * @param frame the frame
     * @param waitIfInACallback the wait if in a callback
     */
    public void sendShieldFrame(ShieldFrame frame, boolean waitIfInACallback) {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }

        if (!waitIfInACallback) {
            sendFrame(frame);
            return;
        }

        boolean inACallback;

        synchronized (arduinoCallbacksLock) {
            inACallback = isInACallback;
        }

        if (inACallback) {
            queuedFrames.add(frame);
        } else {
            if (queuedFrames.isEmpty()) {
                sendFrame(frame);
            } else {
                queuedFrames.add(frame);
            }
        }
    }

    /**
     * Send shield frame.
     *
     * @param frame the frame
     */
    public void sendShieldFrame(ShieldFrame frame) {
        sendShieldFrame(frame, false);
    }

    private void sendFrame(ShieldFrame frame) {
        if (frame == null) return;
        byte[] frameBytes = frame.getAllFrameAsBytes();
        sendData(frameBytes);
        Log.d("Device " + this.name + ": Frame sent, values: " + frame + ".");
    }

    private void respondToIsAlive() {
        synchronized (sendingDataLock) {
            sysex(IS_ALIVE, new byte[]{});
        }
    }

    /**
     * Send serial data.
     *
     * @param data the data
     */
    public void sendSerialData(byte[] data) {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        sendData(data);
        Log.d("Device " + this.name + ": Serial data sent, values: " + ArrayUtils.toHexString(data) + ".");
    }

    private void sendData(byte[] data) {
        int maxShieldFrameBytes = (MAX_OUTPUT_BYTES - 3) / 2;
        ArrayList<byte[]> subArrays = new ArrayList<>();
        for (int i = 0; i < data.length; i += maxShieldFrameBytes) {
            byte[] subArray = (i + maxShieldFrameBytes > data.length) ? ArrayUtils
                    .copyOfRange(data, i, data.length) : ArrayUtils
                    .copyOfRange(data, i, i + maxShieldFrameBytes);
            subArrays.add(subArray);
        }
        synchronized (sendingDataLock) {
            for (byte[] sub : subArrays)
                sysex(SERIAL_DATA, sub);
        }
    }

    private void enableReporting() {
        Log.d("Device " + this.name + ": Enable digital pins reporting.");
        synchronized (sendingDataLock) {
            for (byte i = 0; i < 3; i++) {
                write(new byte[]{(byte) (REPORT_DIGITAL | i), 1});
            }
        }
    }

    private void queryInputPinsValues() {
        Log.d("Device " + this.name + ": Query the current status of the pins.");
        synchronized (sendingDataLock) {
            sysex(REPORT_INPUT_PINS, new byte[]{});
        }
    }

    private void setAllPinsAsInput() {
        Log.d("Device " + this.name + ": Set all digital pins as input.");
        for (int i = 0; i < 20; i++) {
            pinMode(i, INPUT);
        }
    }


    /**
     * Digital read.
     *
     * @param pin the pin
     * @return the boolean
     */
    public boolean digitalRead(int pin) {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return false;
        }
        Log.d("Device " + this.name + ": Digital read from pin " + pin + ".");
        if (pin >= 20 || pin < 0)
            throw new IncorrectPinException("The specified pin number is incorrect, are you sure you specified it correctly?");
        return getDigitalPinStatus(pin);
    }

    private boolean getDigitalPinStatus(int pin){
        return ((digitalInputData[pin >> 3] >> (pin & 0x07)) & 0x01) > 0;
    }

    /**
     * Pin mode.
     *
     * @param pin the pin
     * @param mode the mode
     */
    public void pinMode(int pin, byte mode) {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        Log.d("Device " + this.name + ": Change mode of pin " + pin + " to " + mode + ".");
        if (pin >= 20 || pin < 0)
            throw new IncorrectPinException("The specified pin number is incorrect, are you sure you specified it correctly?");
        byte[] writeData = {SET_PIN_MODE, (byte) pin, mode};
        synchronized (sendingDataLock) {
            write(writeData);
        }
    }

    /**
     * Digital write.
     *
     * @param pin the pin
     * @param value the value
     */
    public void digitalWrite(int pin, boolean value) {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        Log.d("Device " + this.name + ": Digital write " + (value ? "High" : "Low") + " to pin " + pin + ".");
        if (pin >= 20 || pin < 0)
            throw new IncorrectPinException("The specified pin number is incorrect, are you sure you specified it correctly?");
        byte portNumber = (byte) ((pin >> 3) & 0x0F);
        if (!value)
            digitalOutputData[portNumber] &= ~(1 << (pin & 0x07));
        else
            digitalOutputData[portNumber] |= (1 << (pin & 0x07));
        byte[] writeData = {SET_PIN_MODE, (byte) pin, OUTPUT,
                (byte) (DIGITAL_MESSAGE | portNumber),
                (byte) (digitalOutputData[portNumber] & 0x7F),
                (byte) (digitalOutputData[portNumber] >> 7)};
        synchronized (sendingDataLock) {
            write(writeData);
        }
    }

    /**
     * Analog write.
     *
     * @param pin the pin
     * @param value the value
     */
    public void analogWrite(int pin, int value) {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        Log.d("Device " + this.name + ": Analog write " + value + " to pin " + pin + ".");
        if (pin >= 20 || pin < 0)
            throw new IncorrectPinException("The specified pin number is incorrect, are you sure you specified it correctly?");
        byte[] writeData = {SET_PIN_MODE, (byte) pin, PWM,
                (byte) (ANALOG_MESSAGE | (pin & 0x0F)), (byte) (value & 0x7F),
                (byte) (value >> 7)};
        synchronized (sendingDataLock) {
            write(writeData);
        }
    }

    private void setDigitalInputs(int portNumber, int portData) {
        int portDifference = digitalInputData[portNumber] ^ portData;
        digitalInputData[portNumber] = portData;
        ArrayList<Integer> differentPinNumbers = new ArrayList();
        for (int i = 0; i < 8; i++) {
            if (BitsUtils.isBitSet((byte) portDifference, i)) differentPinNumbers.add(i);
        }

        for (int pinNumber : differentPinNumbers) {
            int actualPinNumber = (portNumber << 3) + pinNumber;
            Log.d("Device " + this.name + ": Pin #" + actualPinNumber + " status changed to " + (getDigitalPinStatus(actualPinNumber) ? "High" : "Low") + ".");
            for (OneSheeldDataCallback oneSheeldDataCallback : dataCallbacks) {
                oneSheeldDataCallback.onDigitalPinStatusChange(actualPinNumber, getDigitalPinStatus(actualPinNumber));
            }
        }
    }

    /**
     * Query firmware version.
     */
    public void queryFirmwareVersion() {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        Log.d("Device " + this.name + ": Query firmware version.");
        isFirmwareVersionQueried = false;
        write(REPORT_VERSION);
    }

    /**
     * Query library version.
     */
    public void queryLibraryVersion() {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        Log.d("Device " + this.name + ": Query library version.");
        isLibraryVersionQueried = false;
        sendShieldFrame(new ShieldFrame(CONFIGURATION_SHIELD_ID, QUERY_LIBRARY_VERSION));
    }


    /**
     * Has responded to firmware version query.
     *
     * @return the boolean
     */
    public boolean hasRespondedToFirmwareVersionQuery() {
        return isFirmwareVersionQueried;
    }

    /**
     * Has responded to library version query.
     *
     * @return the boolean
     */
    public boolean hasRespondedToLibraryVersionQuery() {
        return isLibraryVersionQueried;
    }

    private void write(byte[] writeData) {
        if (isConnected() && connectedThread != null && connectedThread.isAlive())
            connectedThread.write(writeData);
    }

    private void write(byte writeData) {
        if (isConnected() && connectedThread != null && connectedThread.isAlive())
            connectedThread.write(new byte[]{writeData});
    }

    private void initFirmware() {
        isBluetoothBufferWaiting = false;
        isSerialBufferWaiting = false;
        isMuted = false;
        arduinoLibraryVersion = -1;
        stopBuffersThreads();
        clearAllBuffers();
        resetProcessInput();
        bluetoothBufferListeningThread = new BluetoothBufferListeningThread();
        serialBufferListeningThread = new SerialBufferListeningThread();
        while (!isBluetoothBufferWaiting) ;
        while (!isSerialBufferWaiting) ;
        enableReporting();
        setAllPinsAsInput();
        queryInputPinsValues();
        respondToIsAlive();
        queryFirmwareVersion();
        notifyHardwareOfConnection();
        queryLibraryVersion();
    }

    /**
     * Mute void.
     */
    public void mute() {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        synchronized (sendingDataLock) {
            sysex(MUTE_FIRMATA, new byte[]{1});
        }
        Log.d("Device " + this.name + ": Communications muted.");
        isMuted = true;
    }

    /**
     * Un mute.
     */
    public void unMute() {
        if (!isConnected()) {
            onError(OneSheeldError.DEVICE_NOT_CONNECTED);
            return;
        }
        synchronized (sendingDataLock) {
            sysex(MUTE_FIRMATA, new byte[]{0});
        }
        Log.d("Device " + this.name + ": Communications unmuted.");
        isMuted = false;
    }

    /**
     * Is muted.
     *
     * @return the boolean
     */
    public boolean isMuted() {
        return isMuted;
    }

    /**
     * Is arduino in a callback.
     *
     * @return the boolean
     */
    public boolean isArduinoInACallback() {
        synchronized (arduinoCallbacksLock) {
            return isInACallback;
        }
    }

    private void onSysex(byte command, byte[] data) {

    }

    private void onLibraryVersionQueryResponse(int version) {
        for (OneSheeldVersionQueryCallback versionQueryCallback : versionQueryCallbacks) {
            versionQueryCallback.onLibraryVersionQueryResponse(version);
        }
    }

    private void onFirmwareVersionQueryResponse(int majorVersion, int minorVersion) {
        for (OneSheeldVersionQueryCallback versionQueryCallback : versionQueryCallbacks) {
            versionQueryCallback.onFirmwareVersionQueryResponse(new FirmwareVersion(majorVersion, minorVersion));
        }
    }

    private void setVersion(int majorVersion, int minorVersion) {
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        Log.d("Device " + this.name + ": Device replied with firmware version, major: " + majorVersion + ", minor:" + minorVersion + ".");
        onFirmwareVersionQueryResponse(majorVersion, minorVersion);
    }

    /**
     * Gets firmware version.
     *
     * @return the firmware version
     */
    public FirmwareVersion getFirmwareVersion() {
        return new FirmwareVersion(majorVersion, minorVersion);
    }

    /**
     * Gets library version.
     *
     * @return the library version
     */
    public int getLibraryVersion() {
        return arduinoLibraryVersion;
    }

    private void processInput(byte inputData) {
        byte command;
        if (parsingSysex) {
            if (inputData == END_SYSEX) {
                parsingSysex = false;
                byte sysexCommand = storedInputData[0];
                if (sysexBytesRead > 0) {
                    byte[] sysexData = new byte[sysexBytesRead - 1];

                    System.arraycopy(storedInputData, 1, sysexData, 0,
                            sysexBytesRead - 1);

                    byte[] fixedSysexData = null;
                    if (sysexData.length % 2 == 0) {
                        fixedSysexData = new byte[sysexData.length / 2];
                        for (int i = 0; i < sysexData.length; i += 2) {
                            fixedSysexData[i / 2] = (byte) (sysexData[i] | (sysexData[i + 1] << 7));
                        }

                        if (sysexCommand == SERIAL_DATA) {
                            for (byte b : fixedSysexData) {
                                serialBuffer.add(b);
                            }
                        } else if (sysexCommand == BLUETOOTH_RESET) {
                            byte randomVal = (byte) (Math.random() * 255);
                            byte complement = (byte) (255 - randomVal & 0xFF);
                            synchronized (sendingDataLock) {
                                sysex(BLUETOOTH_RESET, new byte[]{0x01, randomVal, complement});
                            }
                            Log.d("Device " + this.name + ": Device requested Bluetooth reset.");
                            closeConnection();
                        } else if (sysexCommand == IS_ALIVE) {
                            respondToIsAlive();
                        } else {
                            onSysex(sysexCommand, sysexData);
                        }
                    }
                } else {
                    onSysex(sysexCommand, new byte[]{});
                }

            } else {
                if (sysexBytesRead < storedInputData.length) {
                    storedInputData[sysexBytesRead] = inputData;
                    sysexBytesRead++;
                }
            }
        } else if (waitForData > 0 && (int) (inputData & 0xFF) < 128) {
            waitForData--;
            storedInputData[waitForData] = inputData;
            if (executeMultiByteCommand != 0 && waitForData == 0) {
                switch (executeMultiByteCommand) {
                    case DIGITAL_MESSAGE:
                        setDigitalInputs(multiByteChannel,
                                (storedInputData[0] << 7) + storedInputData[1]);
                        break;
                    case REPORT_VERSION:
                        setVersion(storedInputData[0], storedInputData[1]);
                        isFirmwareVersionQueried = true;
                        break;
                }
            }
        } else {
            if ((int) (inputData & 0xFF) < 0xF0) {
                command = (byte) (inputData & 0xF0);
                multiByteChannel = (byte) (inputData & 0x0F);
            } else {
                command = inputData;
            }
            switch (command) {
                case START_SYSEX:
                    parsingSysex = true;
                    sysexBytesRead = 0;
                    break;
                case DIGITAL_MESSAGE:
                case REPORT_VERSION:
                    waitForData = 2;
                    executeMultiByteCommand = command;
                    break;
            }
        }
    }

    /**
     * Disconnect void.
     */
    public void disconnect() {
        Log.d("Device " + this.name + ": Disconnection request received.");
        closeConnection();
    }

    /**
     * Connect void.
     */
    public void connect() {
        Log.d("Device " + this.name + ": Delegate the connection request to the manager.");
        manager.connect(this);
    }

    /**
     * Is connected.
     *
     * @return the boolean
     */
    public boolean isConnected() {
        synchronized (isConnectedLock) {
            return isConnected;
        }
    }

    private synchronized void closeConnection() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread.interrupt();
            connectedThread = null;
        }
        boolean isConnected;
        synchronized (isConnectedLock) {
            isConnected = this.isConnected;
            this.isConnected = false;
        }
        if (callbacksTimeout != null) callbacksTimeout.stopTimer();
        if (exitingCallbacksThread != null && exitingCallbacksThread.isAlive())
            exitingCallbacksThread.interrupt();
        if (enteringCallbacksThread != null && enteringCallbacksThread.isAlive())
            enteringCallbacksThread.interrupt();
        queuedFrames.clear();
        synchronized (arduinoCallbacksLock) {
            isInACallback = false;
        }
        if (isConnected) {
            Log.d("Device " + this.name + ": Device disconnected.");
            onDisconnect();
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        /**
         * The Write handler.
         */
        Handler writeHandler;
        /**
         * The Write handler looper.
         */
        Looper writeHandlerLooper;
        /**
         * The Looper thread.
         */
        Thread LooperThread;

        /**
         * Instantiates a new Connected thread.
         *
         * @param socket the socket
         */
        ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            setName("OneSheeldConnectedThread: " + socket.getRemoteDevice().getAddress());
        }

        @Override
        public void run() {
            if (mmSocket == null) return;
            Log.d("Device " + OneSheeldDevice.this.name + ": Establishing connection.");
            LooperThread = new Thread(new Runnable() {

                @Override
                public void run() {
                    Looper.prepare();
                    writeHandlerLooper = Looper.myLooper();
                    writeHandler = new Handler();
                    Looper.loop();
                }
            });
            LooperThread.start();
            while (!LooperThread.isAlive()) ;
            synchronized (isConnectedLock) {
                isConnected = true;
            }
            byte[] buffer = new byte[MAX_BUFFER_SIZE];
            int bufferLength;
            Log.d("Device " + OneSheeldDevice.this.name + ": Initializing board and querying its information.");
            initFirmware();
            Log.d("Device " + OneSheeldDevice.this.name + ": Device connected, initialized and ready for communication.");
            onConnect();
            while (!this.isInterrupted()) {
                try {
                    bufferLength = mmInStream.read(buffer, 0, buffer.length);
                    bufferLength = bufferLength >= buffer.length ? buffer.length : bufferLength;
                    for (int i = 0; i < bufferLength; i++) {
                        bluetoothBuffer.add(buffer[i]);
                    }
                } catch (IOException e) {
                    if (writeHandlerLooper != null)
                        writeHandlerLooper.quit();
                    closeConnection();
                    break;
                }
            }
        }

        private synchronized void write(final byte[] buffer) {
            if (writeHandler == null)
                return;
            writeHandler.post(new Runnable() {

                @Override
                public void run() {
                    try {
                        mmOutStream.write(buffer);
                    } catch (IOException e) {
                        if (writeHandlerLooper != null)
                            writeHandlerLooper.quit();
                        closeConnection();
                    }
                }
            });
        }

        /**
         * Cancel void.
         */
        synchronized void cancel() {
            if (mmInStream != null)
                try {
                    mmInStream.close();
                } catch (IOException ignored) {
                }
            if (mmOutStream != null)
                try {
                    mmOutStream.close();
                } catch (IOException e) {
                }
            if (mmSocket != null)
                try {
                    mmSocket.close();
                } catch (IOException ignored) {
                }
        }
    }

    private class BluetoothBufferListeningThread extends Thread {
        /**
         * Instantiates a new Bluetooth buffer listening thread.
         */
        BluetoothBufferListeningThread() {
            start();
        }

        private void stopRunning() {
            if (this.isAlive())
                this.interrupt();
        }

        @Override
        public void run() {
            int input;
            while (!this.isInterrupted()) {
                try {
                    input = readByteFromBluetoothBuffer();
                    processInput((byte) input);
                    for (OneSheeldDataCallback oneSheeldDataCallback : dataCallbacks) {
                        oneSheeldDataCallback.onSerialDataReceive(input);
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private class SerialBufferListeningThread extends Thread {
        /**
         * Instantiates a new Serial buffer listening thread.
         */
        SerialBufferListeningThread() {
            start();
        }

        private void stopRunning() {
            if (this.isAlive())
                this.interrupt();
        }

        @Override
        public void run() {
            while (!this.isInterrupted()) {
                try {
                    while ((readByteFromSerialBuffer()) != ShieldFrame.START_OF_FRAME)
                        ;
                    if (ShieldFrameTimeout != null)
                        ShieldFrameTimeout.stopTimer();
                    ShieldFrameTimeout = new TimeOut(1000);
                    int tempArduinoLibVersion = readByteFromSerialBuffer();
                    byte shieldId = readByteFromSerialBuffer();
                    byte instanceId = readByteFromSerialBuffer();
                    byte functionId = readByteFromSerialBuffer();
                    ShieldFrame frame = new ShieldFrame(shieldId, instanceId,
                            functionId);
                    int argumentsNumber = readByteFromSerialBuffer() & 0xFF;
                    int argumentsNumberVerification = (255 - (readByteFromSerialBuffer() & 0xFF));
                    if (argumentsNumber != argumentsNumberVerification) {
                        Log.d("Device " + OneSheeldDevice.this.name + ": Frame is incorrect, canceling what we've read so far.");
                        if (ShieldFrameTimeout != null)
                            ShieldFrameTimeout.stopTimer();
                        serialBuffer.clear();
                        continue;
                    }
                    for (int i = 0; i < argumentsNumber; i++) {
                        int length = readByteFromSerialBuffer() & 0xFF;
                        int lengthVerification = (255 - (readByteFromSerialBuffer() & 0xFF));
                        if (length != lengthVerification || length <= 0) {
                            Log.d("Device " + OneSheeldDevice.this.name + ": Frame is incorrect, canceling what we've read so far.");
                            if (ShieldFrameTimeout != null)
                                ShieldFrameTimeout.stopTimer();
                            serialBuffer.clear();
                            continue;
                        }
                        byte[] data = new byte[length];
                        for (int j = 0; j < length; j++) {
                            data[j] = readByteFromSerialBuffer();
                        }
                        frame.addArgument(data);
                    }
                    if ((readByteFromSerialBuffer()) != ShieldFrame.END_OF_FRAME) {
                        Log.d("Device " + OneSheeldDevice.this.name + ": Frame is incorrect, canceling what we've read so far.");
                        if (ShieldFrameTimeout != null)
                            ShieldFrameTimeout.stopTimer();
                        serialBuffer.clear();
                        continue;
                    }
                    if (ShieldFrameTimeout != null)
                        ShieldFrameTimeout.stopTimer();
                    if (arduinoLibraryVersion != tempArduinoLibVersion) {
                        arduinoLibraryVersion = tempArduinoLibVersion;
                        isLibraryVersionQueried = true;
                        Log.d("Device " + OneSheeldDevice.this.name + ": Device replied with library version: " + arduinoLibraryVersion + ".");
                        onLibraryVersionQueryResponse(arduinoLibraryVersion);
                    }

                    if (shieldId == CONFIGURATION_SHIELD_ID) {
                        if (functionId == LIBRARY_VERSION_RESPONSE) {
                        } else if (functionId == IS_HARDWARE_CONNECTED_QUERY) {
                            notifyHardwareOfConnection();
                        } else if (functionId == IS_CALLBACK_ENTERED) {
                            callbackEntered();
                        } else if (functionId == IS_CALLBACK_EXITED) {
                            callbackExited();
                        }
                    } else {
                        Log.d("Device " + OneSheeldDevice.this.name + ": Frame received, values: " + frame + ".");
                        for (OneSheeldDataCallback oneSheeldDataCallback : dataCallbacks) {
                            oneSheeldDataCallback.onShieldFrameReceive(frame);
                            if (OneSheeldSdk.getKnownShields().contains(shieldId) &&
                                    OneSheeldSdk.getKnownShields().getKnownShield(shieldId).getKnownFunctions().contains(KnownFunction.getFunctionWithId(functionId)))
                                oneSheeldDataCallback.onKnownShieldFrameReceive(OneSheeldSdk.getKnownShields().getKnownShield(shieldId), frame);
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (ShieldFrameNotComplete e) {
                    Log.d("Device " + OneSheeldDevice.this.name + ": Frame wasn't completed in 1 second, canceling what we've read so far.");
                    if (ShieldFrameTimeout != null)
                        ShieldFrameTimeout.stopTimer();
                    ShieldFrameTimeout = null;
                    continue;
                }
            }
        }
    }

    private class ShieldFrameNotComplete extends Exception {
    }
}
