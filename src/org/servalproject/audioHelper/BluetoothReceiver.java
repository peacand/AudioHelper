/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.servalproject.audioHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import org.servalproject.audio.AudioPlayer;
import org.servalproject.batphone.VoMP;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class BluetoothReceiver {
    // Debugging
    private static final String TAG = "BluetoothReceiver";

    // Name for the SDP record when creating server socket
    private static final String NAME = "BluetoothReceiver";

    // Unique UUID for this application
    private static final UUID MY_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private AcceptThread mAcceptThread;
    private ConnectedThread mConnectedThread;
    private AudioPlayer mPlayer;
    private InputStream mInStream;
    private BluetoothSocket mSocket;

    public BluetoothReceiver(Context context) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mPlayer = new AudioPlayer(null, context);
    }

    public void start() {
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Start the thread to listen on a BluetoothServerSocket
        if (mAcceptThread == null) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }
    }


    public void connected(BluetoothSocket socket, BluetoothDevice device) {
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Cancel the accept thread because we only want to connect to one device
        //if (mAcceptThread != null) {mAcceptThread.cancel(); mAcceptThread = null;}

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
    }

    public void stop() {
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
        if (mAcceptThread != null) {mAcceptThread.cancel(); mAcceptThread = null;}
    }

    private void connectionFailed() {
        Log.v(TAG, "connection failed");
    }

    private void connectionLost() {
        Log.v(TAG, "connection lost");
    }

    public void executeMethod(String methodName) {
        if( methodName.charAt(0) != '*' )
            Log.v(TAG, "MethodCall : " + methodName );

        try {
            if( methodName.equals("prepareAudio") )
                mPlayer.prepareAudio();
            if( methodName.equals("startPlaying") )
                mPlayer.startPlaying();
            if( methodName.equals("stopPlaying") )
                mPlayer.stopPlaying();
            if( methodName.equals("cleanup") )
                mPlayer.cleanup();
            if( methodName.charAt(0) == '*' )
                processAudioData( methodName.substring(1) );
        } catch (Exception e) {
            Log.v(TAG, "Error while calling " + methodName);
        }
    }

    public void processAudioData(String header) {
        Log.v(TAG, "Audio : " + header );

        String fields[] = new String[7];
        fields = header.split(":", 7);
        
        int byteCount = Integer.parseInt(fields[0]);
        int local_session = Integer.parseInt(fields[1]);
        int start_time = Integer.parseInt(fields[2]);
        int jitter_delay = Integer.parseInt(fields[3]);
        int this_delay = Integer.parseInt(fields[4]);
        int codec_code = Integer.parseInt(fields[5]);
        int codec_preference = Integer.parseInt(fields[6]);

        try {
            mPlayer.receivedAudio(local_session, start_time, jitter_delay, this_delay, VoMP.Codec.getCodec(codec_code), mInStream, byteCount);
        } catch (Exception e) {
           Log.v(TAG, "Problem with receivedAudio processing");
        }
    }

    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try {
                tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            setName("AcceptThread");
            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (true) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothReceiver.this) {
                            connected(socket, socket.getRemoteDevice());
                    }
                }
            }
        }

        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of server failed", e);
            }
        }
    }


    private class ConnectedThread extends Thread {

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "create ConnectedThread");
            mSocket = socket;
            InputStream tmpIn = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mInStream = tmpIn;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            char methodName[] = new char[126];
            int bytes, index = 0;

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mInStream.read();
                    if( bytes == '\n' ) {
                        executeMethod( new String(methodName, 0, index) );
                        index = 0;
                    }
                    else if (bytes > 0) {
                        methodName[index] = (char) bytes;
                        index++;
                    }
                    
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        public void cancel() {
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
