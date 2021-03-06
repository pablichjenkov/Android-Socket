package com.letmeaccess.usb.aoa;

import android.hardware.usb.UsbAccessory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import com.letmeaccess.usb.Socket;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


/* package */ class UsbAoaSocket implements Socket {

    private static final String TAG = "UsbAoaSocket";
    private UsbAoaManager mAoaManager;
    private UsbAccessory mAccessory;
    protected AccessoryListener mListener;
    private ParcelFileDescriptor mFileDescriptor;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private ReceiverThread mReceiverThread;
    private SenderThread mSenderThread;

    private boolean mIsPendingPermission;
    private boolean mIsConnected;
    private boolean mIsDisconnecting;
    private boolean isError;

    /* package */ UsbAoaSocket(@NonNull UsbAoaManager aoaManager, @NonNull UsbAccessory accessory
            , @NonNull AccessoryListener listener) {

        mAoaManager = aoaManager;
        mAccessory = accessory;
        mListener = listener;
    }

    /* package */ void setPendingPermission(boolean pendingPermission) {
        mIsPendingPermission = pendingPermission;
    }

    /* package */ boolean isPendingPermission() {
        return mIsPendingPermission;
    }

    @Override
    public void open() {
        if (mIsConnected) {
            mListener.onOpen();
            return;
        }

        mFileDescriptor = mAoaManager.provideManager().openAccessory(mAccessory);

        if (mFileDescriptor != null) {
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mOutputStream = new FileOutputStream(fd);
            mInputStream = new FileInputStream(fd);

            // Prepare handler threads
            mReceiverThread = new ReceiverThread("UsbAoaSocket.ReaderThread");
            mReceiverThread.start();

            mSenderThread = new SenderThread("UsbAoaSocket.SenderThread");
            mSenderThread.start();

            mIsConnected = true;
            mListener.onOpen();

        } else {
            mIsConnected = false;
            mListener.onError(AccessoryError.OpenFail);
        }
    }

    @Override
    public boolean isConnected() {
        return mIsConnected;
    }

    @Override
    public void write(byte[] data) {
        if (mIsConnected) {
            mSenderThread.send(data);
        }
    }

    @Override
    public void close() {
        if (mIsConnected) {
            mIsConnected = false;
            mIsDisconnecting = true;
            mReceiverThread.close();
            mSenderThread.send(mListener.onProvideCloseCommand());
        }
    }

    private void closeStream() {
        try {
            mFileDescriptor.close();
            mInputStream.close();
            mOutputStream.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleDataReceived(byte[] inboundData) {
        mListener.onRead(inboundData);
    }

    /**
     * It maybe called multiple times since closing a stream in one direction may generate an error
     * in the other direction. We attend only the first call.
     */
    private void handleError() {
        if (!isError) {
            isError = true;
            mIsConnected = false;
            closeStream();
            mAoaManager.disposeAoaSocket(this);
        }
    }

    class ReceiverThread extends HandlerThread {

        private static final int MAX_BUF_SIZE = 1024;
        private Handler mHandler;
        private boolean mThreadRunning;


        public ReceiverThread(String name) {
            super(name);
        }

        @Override
        protected void onLooperPrepared() {
            mHandler = new Handler(getLooper());
            mHandler.post(mRunnable);
        }

        public void close() {
            mThreadRunning = false;
        }

        private Runnable mRunnable = new Runnable() {
            @Override
            public void run() {
                int length = 0;
                byte[] reusedBuffer = new byte[MAX_BUF_SIZE];
                byte[] inboundData;
                mThreadRunning = true;

                // Receiving loop
                while (mThreadRunning) {
                    try {

                        length = mInputStream.read(reusedBuffer);

                        if (length >= 0) {
                            inboundData = new byte[length];
                            System.arraycopy(reusedBuffer, 0, inboundData, 0, length);
                            handleDataReceived(inboundData);

                        } else {
                            handleError();
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        mThreadRunning = false;
                        handleError();
                    }
                }

                // Before leaving the Thread close the inputStream.
                try {

                    mHandler.removeCallbacksAndMessages(null);
                    mHandler.getLooper().quit();

                    if (mIsDisconnecting) {
                        mSenderThread.close();
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        };

    }

    class SenderThread extends HandlerThread {

        private Handler mHandler;
        private boolean mThreadStarted;


        public SenderThread(String name) {
            super(name);
        }

        @Override
        public synchronized void start() {
            super.start();
            mHandler = new Handler(getLooper());
            mThreadStarted = true;
        }

        public void send(final byte[] outboundData) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {

                        if (mThreadStarted) {
                            mOutputStream.write(outboundData);
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                        handleError();
                    }
                }
            });
        }

        public void close() {
            mThreadStarted = false;
            mHandler.removeCallbacksAndMessages(null);
            mHandler.getLooper().quit();

            if (mIsDisconnecting) {
                closeStream();
            }
        }

    }

    // region: package private

    /* package */ void handleAccessoryDetach() {
        handleError();
    }

    /* package */ UsbAccessory getAccessory() {
        return mAccessory;
    }

    // endregion

}
