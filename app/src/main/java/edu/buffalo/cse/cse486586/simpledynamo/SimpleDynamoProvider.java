package edu.buffalo.cse.cse486586.simpledynamo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

import static android.content.ContentValues.TAG;

public class SimpleDynamoProvider extends ContentProvider {
    static final String REMOTE_PORT0 = "11108"; //emulator-5554
    static final String REMOTE_PORT1 = "11112"; //emulator-5556
    static final String REMOTE_PORT2 = "11116"; //emulator-5558
    static final String REMOTE_PORT3 = "11120"; //emulator-5560
    static final String REMOTE_PORT4 = "11124"; //emulator-5562
    static final int SERVER_PORT = 10000;

    static final String INSERT = "insert";
    static final String INSERT_SUCCESS = "insert_success";
    static final String INSERT_COMPLETE = "insert_complete";
    static final String REPLICATE1 = "replicate1";
    static final String REPLICATE_SUCCESS = "replicate_success";
    static final String REPLICATE1_NOREPLY = "replicate1_noreply";
    static final String REPLICATE2 = "replicate2";
    static final String REPLICA2_SUCCESS = "replica2_success";
    static final String DELETE = "delete";
    static final String DELETE_SUCCESS = "delete_success";
    static final String DELETE_REPLICA1 = "delete_replica1";
    static final String DELETE_REPLICA_SUCCESS = "delete_replica_success";
    static final String DELETE_REPLICA1_NOREPLY = "delete_replica1_noreply";
    static final String DELETE_REPLICA2 = "delete_replica2";
    static final String QUERY = "query";
    static final String QUERY_RESPONSE = "query_response";
    static final String QUERY_ALL = "query_all";
    static final String QUERY_ALL_RESPONSE = "query_all_response";
    static final String REVIVAL_SUCC = "revival_succ";
    static final String REVIVAL_PRED = "revival_pred";
    static final String REVIVAL_RESPONSE = "revival_response";

    //This works on my machine with this timeout. If it doesnt work for you, increase it.//
    static final int timeout = 1;

    private String myPort;
    private ArrayList<String> ringOrder;
    private ArrayBlockingQueue<String> qResponse;
    private ArrayBlockingQueue<String> gdumpBlocker;
    private ArrayBlockingQueue<String> insertResponse;
    private ArrayBlockingQueue<String> replicaResponse;
    private ArrayBlockingQueue<String> deleteResponse;
    private ArrayBlockingQueue<String> deleteReplicaResponse;
    private ArrayBlockingQueue<String> insertCompleteResponse;
    private ArrayBlockingQueue<String> replica2Response;
    private ArrayList<String> globalDump;

    private ReentrantLock queryLock;
    private ReentrantLock insertLock;
    private ReentrantLock deleteLock;
    private ReentrantLock replicaLock;
    private ReentrantLock replica2Lock;
    private ReentrantLock deleteReplicaLock;

    /*
       FULL DISCLAIMER: My code is extremely verbose and there's a lot unnecessary duplicate code.
       This is because in the interest of time, instead of thinking about unnecessary ways to organize
       the code, I just did everything in the "most obvious" way. Sorry it's so bloated!
     */

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Log.v("delete", selection);
        if (selection.equals("@")) {
            //Delete everything locally
            String[] allKeys = getContext().fileList();
            for (String key : allKeys) {
                getContext().deleteFile(key);
            }
            return 0;
        }
        String targetPort = getOwner(selection);
        Log.e("Waiting to aqcuire", "deleteLock");
        deleteLock.lock();
        Log.e("Aqcuired", "deleteLock");
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE, selection, targetPort);
        //Wait for confirmation that delete was processed by targetPort
        String response = null;
        try {
            Log.e("Waiting on", "deleteResponse");
            response = deleteResponse.poll(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //After a timeout, assume targetPort is dead, and just DELETE_REPLICA1_NOREPLY to targetPort's successor
        if (response == null) {
            Log.e(targetPort + " timed out", "deleteResponse");
            new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE_REPLICA1_NOREPLY, selection, getSucc(targetPort));
        }
        deleteLock.unlock();
        Log.e("Released", "deleteLock");
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.v("insert", values.toString());
        String key = values.getAsString("key");
        String value = values.getAsString("value");
        String targetPort = getOwner(key);

        Log.e("Waiting to aqcuire", "insertLock");
        insertLock.lock();
        Log.e("Aqcuired", "insertLock");
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, INSERT, key, value, targetPort);

        //Wait for confirmation that insert was processed by targetPort
        String response = null;
        try {
            Log.e("Waiting on", "insertResponse");
            response = insertResponse.poll(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //After a timeout, assume targetPort is dead, and just REPLICATE1 to targetPort's successor
        if (response == null) {
            Log.e(targetPort + " timed out", "insertResponse");
            new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REPLICATE1_NOREPLY, key, value, getSucc(targetPort));
        }

        //Wait for confirmation that insert was processed by entire preference list before returning
        try {
            Log.e("Waiting on", "insertCompleteResponse");
            insertCompleteResponse.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        insertLock.unlock();
        Log.e("Released", "insertLock");
        return uri;
    }

    public void storeKVP(String key, String value) {
        FileOutputStream outputStream;
        try {
            outputStream = getContext().openFileOutput(key, Context.MODE_PRIVATE);
            outputStream.write(value.getBytes());
            outputStream.close();
        } catch (Exception e) {
            Log.e(TAG, "File write failed");
        }
    }

    @Override
    public boolean onCreate() {
        //Populate full view of ring (hard-coding order is fine for PA4)
        ringOrder = new ArrayList<String>();
        ringOrder.add("11124");
        ringOrder.add("11112");
        ringOrder.add("11108");
        ringOrder.add("11116");
        ringOrder.add("11120");

        //Get my port
        TelephonyManager tel = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        myPort = String.valueOf((Integer.parseInt(portStr) * 2));

        //Boot up our server so we can receive stuff
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            e.printStackTrace();
        }

        qResponse = new ArrayBlockingQueue<String>(10);
        globalDump = new ArrayList<String>();
        gdumpBlocker = new ArrayBlockingQueue<String>(10);
        insertResponse = new ArrayBlockingQueue<String>(10);
        replicaResponse = new ArrayBlockingQueue<String>(10);
        deleteResponse = new ArrayBlockingQueue<String>(10);
        deleteReplicaResponse = new ArrayBlockingQueue<String>(10);
        insertCompleteResponse = new ArrayBlockingQueue<String>(10);
        replica2Response = new ArrayBlockingQueue<String>(10);

        queryLock = new ReentrantLock();
        insertLock = new ReentrantLock();
        deleteLock = new ReentrantLock();
        deleteReplicaLock = new ReentrantLock();
        replicaLock = new ReentrantLock();
        replica2Lock = new ReentrantLock();

        //Contact predecessor and successor and recover replicas and missed writes
        //We should delete everything leftover on revival. Everything will be restored.

        delete(null, "@", null);
        String succPort = getSucc(myPort);
        String predPort = getPred(myPort);
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REVIVAL_SUCC, succPort);
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REVIVAL_PRED, predPort);

        return false;
    }

    //Given a key, determines which node is responsible for that key
    public String getOwner(String key) {
        try {
            String keyHash = genHash(key);
            //If keyHash is less than the first node in the ring, that node gets it
            if (keyHash.compareTo(portToNodeID(ringOrder.get(0))) <= 0) {
                return ringOrder.get(0);
            }
            //If keyHash is between two nodes, the latter node gets it
            for (int i = 1; i < ringOrder.size(); i++) {
                String predID = portToNodeID(ringOrder.get(i - 1));
                String curID = portToNodeID(ringOrder.get(i));

                if (keyHash.compareTo(predID) >= 0 && keyHash.compareTo(curID) <= 0) {
                    return ringOrder.get(i);
                }
            }
            //If keyHash is greater than the final node, the first node gets it
            return ringOrder.get(0);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "something bad happened in getOwner";
    }

    public String getSucc(String port) {
        for (int i = 0; i < ringOrder.size(); i++) {
            if (ringOrder.get(i).equals(port)) {
                if ((i + 1) == ringOrder.size()) {
                    return ringOrder.get(0);
                }
                return ringOrder.get(i + 1);
            }
        }
        return "something bad happened in getSucc";
    }

    public String getPred(String port) {
        return getSucc(getSucc(getSucc(getSucc(port))));
    }

    public String portToNodeID(String port) throws NoSuchAlgorithmException {
        String emuID = "";
        if (port.equals(REMOTE_PORT0)) {
            emuID = "5554";
        } else if (port.equals(REMOTE_PORT1)) {
            emuID = "5556";
        } else if (port.equals(REMOTE_PORT2)) {
            emuID = "5558";
        } else if (port.equals(REMOTE_PORT3)) {
            emuID = "5560";
        } else if (port.equals(REMOTE_PORT4)) {
            emuID = "5562";
        }
        return genHash(emuID);
    }

    public Cursor buildCursor(String key, String value) {
        String[] col = new String[2];
        col[0] = "key";
        col[1] = "value";
        MatrixCursor curs = new MatrixCursor(col);

        String[] val = new String[2];
        val[0] = key;
        val[1] = value;
        curs.addRow(val);

        return curs;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Log.v("query", selection);
        if (selection.equals("*")) {
            globalDump.clear();
            //Multicast query to all nodes
            new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, QUERY_ALL);
            //Wait for nodes to finish responding.
            try {
                TimeUnit.SECONDS.sleep(timeout);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            //How to do this more deterministically? Poll gDumpBlocker 5 times!
            //What if a node is failed? Use poll() with a timeout!

            //Then build the cursor and return it
            String[] col = new String[2];
            col[0] = "key";
            col[1] = "value";
            MatrixCursor curs = new MatrixCursor(col);
            //Collections.sort(globalDump);
            for (String entry : globalDump) {
                String[] e = entry.split(",");
                String key = e[0];
                String value = e[1];
                String[] val = new String[2];
                val[0] = key;
                val[1] = value;
                Log.e("Adding to cursor ", key + "," + value);
                curs.addRow(val);
            }
            return curs;
        }
        if (selection.equals("@")) {
            //Query everything locally
            String[] allKeys = getContext().fileList();
            String[] col = new String[2];
            col[0] = "key";
            col[1] = "value";
            MatrixCursor curs = new MatrixCursor(col);
            for (String key : allKeys) {
                FileInputStream inputStream = null;
                try {
                    inputStream = getContext().openFileInput(key);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                Scanner s = new Scanner(inputStream);
                String value = s.next();
                String[] val = new String[2];
                val[0] = key;
                val[1] = value;
                curs.addRow(val);
            }
            return curs;
        }
        String owner = getOwner(selection);
        String targetPortBackup = getSucc(owner);
        String targetPort = getSucc(targetPortBackup);
        Log.e("Waiting to aqcuire", "queryLock");
        queryLock.lock();
        Log.e("Aqcuired", "queryLock");
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, QUERY, selection, targetPort);
        String value = null;
        try {
            Log.e("Waiting on", "qResponse");
            value = qResponse.poll(timeout, TimeUnit.SECONDS);
            //If we dont get a response within timeout, assume node is dead and contact its pred
            if (value == null) {
                Log.e(targetPort + " timed out", "qResponse");
                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, QUERY, selection, targetPortBackup);
                value = qResponse.take();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        queryLock.unlock();
        Log.e("Released", "queryLock");
        return buildCursor(selection, value);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }

    private String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    private class ClientTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... msgs) {
            String msgType = msgs[0];

            try {
                if (msgType.equals(INSERT)) {
                    String key = msgs[1];
                    String value = msgs[2];
                    String targetPort = msgs[3];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + value + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(REPLICATE1)) {
                    String key = msgs[1];
                    String value = msgs[2];
                    String targetPort = msgs[3];
                    String origin = msgs[4];

                    Log.e("Waiting to aqcuire", "replicaLock");
                    replicaLock.lock();
                    Log.e("Acquired", "replicaLock");
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + value + "," + myPort + "," + origin + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);

                    //Wait for confirmation that replication was processed by targetPort
                    String response = null;
                    try {
                        Log.e("Waiting on", "replicaResponse");
                        response = replicaResponse.poll(timeout, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    //After a timeout, assume targetPort is dead, and just REPLICATE2 to targetPort's successor
                    if (response == null) {
                        Log.e(targetPort + " timed out", "replicaResponse");
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REPLICATE2, key, value, getSucc(targetPort), origin);
                    }
                    replicaLock.unlock();
                    Log.e("Released", "replicaLock");
                } else if (msgType.equals(REPLICATE1_NOREPLY)) {
                    String key = msgs[1];
                    String value = msgs[2];
                    String targetPort = msgs[3];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + value + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(REPLICATE2)) {
                    String key = msgs[1];
                    String value = msgs[2];
                    String targetPort = msgs[3];
                    String origin = msgs[4];

                    Log.e("Waiting to aqcuire", "replica2Lock");
                    replica2Lock.lock();
                    Log.e("Aqcuired", "replica2Lock");
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + value + "," + myPort + "," + origin + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);

                    //Wait for confirmation that replica2 was stored
                    String response = null;
                    try {
                        Log.e("Waiting on", "replica2Response");
                        response = replica2Response.poll(timeout, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    //After a timeout, assume targetPort is dead, and just send INSERT_COMPLETE to origin
                    if (response == null) {
                        Log.e(targetPort + " timed out", "replica2Response");
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, INSERT_COMPLETE, origin);
                    }
                    replica2Lock.unlock();
                    Log.e("Released", "replica2Lock");
                } else if (msgType.equals(DELETE)) {
                    String key = msgs[1];
                    String targetPort = msgs[2];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(DELETE_REPLICA1)) {
                    String key = msgs[1];
                    String targetPort = msgs[2];

                    Log.e("Waiting to aqcuire", "deleteReplicaLock");
                    deleteReplicaLock.lock();
                    Log.e("Acquired", "deleteReplicaLock");
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);

                    //Wait for confirmation that replica was deleted by targetPort
                    String response = null;
                    try {
                        Log.e("Waiting on", "deleteReplicaResponse");
                        response = deleteReplicaResponse.poll(timeout, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    //After a timeout, assume targetPort is dead, and just DELETE_REPLICA2 to targetPort's successor
                    if (response == null) {
                        Log.e(targetPort + " timed out", "deleteReplicaResponse");
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE_REPLICA2, key, getSucc(targetPort));
                    }
                    deleteReplicaLock.unlock();
                    Log.e("Released", "deleteReplicaLock");
                } else if (msgType.equals(DELETE_REPLICA1_NOREPLY)) {
                    String key = msgs[1];
                    String targetPort = msgs[2];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(DELETE_REPLICA2)) {
                    String key = msgs[1];
                    String targetPort = msgs[2];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(QUERY)) {
                    String key = msgs[1];
                    String targetPort = msgs[2];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + key + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(QUERY_RESPONSE)) {
                    String value = msgs[1];
                    String targetPort = msgs[2];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + value + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(QUERY_ALL)) {
                    for (String nodePort : ringOrder) {
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(nodePort));
                        String message = msgType + "," + myPort + '\n';
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        out.writeBytes(message);
                    }
                } else if (msgType.equals(REVIVAL_SUCC)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(REVIVAL_PRED)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(INSERT_SUCCESS)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(REPLICATE_SUCCESS)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(DELETE_SUCCESS)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(DELETE_REPLICA_SUCCESS)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(INSERT_COMPLETE)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else if (msgType.equals(REPLICA2_SUCCESS)) {
                    String targetPort = msgs[1];

                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(targetPort));
                    String message = msgType + "," + myPort + '\n';
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeBytes(message);
                } else {
                    throw new Error(msgType + " doesn't have a method in ClientTask");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... serverSockets) {
            while (true) {
                ServerSocket serverSocket = serverSockets[0];
                try {
                    Socket socket = serverSocket.accept();
                    Log.e("Socket", "Accepted");
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    String in_text = in.readLine();
                    in.close();
                    Log.e("Received Text", in_text);
                    String[] message = in_text.split(",");

                    String msgType = message[0];

                    if (msgType.equals(INSERT)) {
                        String key = message[1];
                        String value = message[2];
                        String sourcePort = message[3];

                        storeKVP(key, value);
                        //Send confirmation back to sourcePort
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, INSERT_SUCCESS, sourcePort);
                        //Send first replica to successor
                        String targetPort = getSucc(myPort);

                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REPLICATE1, key, value, targetPort, sourcePort);
                    } else if (msgType.equals(REPLICATE1)) {
                        String key = message[1];
                        String value = message[2];
                        String sourcePort = message[3];
                        String origin = message[4];

                        storeKVP(key, value);
                        //Send confirmation back to sourcePort
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REPLICATE_SUCCESS, sourcePort);
                        //Send second replica to successor
                        String targetPort = getSucc(myPort);
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REPLICATE2, key, value, targetPort, origin);
                    } else if (msgType.equals(REPLICATE1_NOREPLY)) {
                        String key = message[1];
                        String value = message[2];
                        String sourcePort = message[3];
                        //If using storeKVP here doesn't work, I might need to use insert() itself
                        storeKVP(key, value);
                        //Send second replica to successor
                        String targetPort = getSucc(myPort);
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REPLICATE2, key, value, targetPort, sourcePort);
                    } else if (msgType.equals(REPLICATE2)) {
                        String key = message[1];
                        String value = message[2];
                        String sourcePort = message[3];
                        String origin = message[4];

                        storeKVP(key, value);
                        //Send confirmation back to sourcePort
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, REPLICA2_SUCCESS, sourcePort);
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, INSERT_COMPLETE, origin);
                    } else if (msgType.equals(DELETE)) {
                        String key = message[1];
                        String sourcePort = message[2];
                        //If this doesnt work here, may need to use delete() itself
                        getContext().deleteFile(key);

                        //Send confirmation back to sourcePort
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE_SUCCESS, sourcePort);

                        //Tell successor to delete its replica
                        String targetPort = getSucc(myPort);

                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE_REPLICA1, key, targetPort);
                    } else if (msgType.equals(DELETE_REPLICA1)) {
                        String key = message[1];
                        String sourcePort = message[2];
                        //If this doesnt work here, may need to use delete() itself
                        getContext().deleteFile(key);
                        //Send confirmation back to sourcePort
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE_REPLICA_SUCCESS, sourcePort);
                        //Tell successor to delete its replica
                        String targetPort = getSucc(myPort);
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE_REPLICA2, key, targetPort);
                    } else if (msgType.equals(DELETE_REPLICA1_NOREPLY)) {
                        String key = message[1];
                        String sourcePort = message[2];
                        //If this doesnt work here, may need to use delete() itself
                        getContext().deleteFile(key);
                        //Tell successor to delete its replica
                        String targetPort = getSucc(myPort);
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, DELETE_REPLICA2, key, targetPort);
                    } else if (msgType.equals(DELETE_REPLICA2)) {
                        String key = message[1];
                        String sourcePort = message[2];
                        //If this doesnt work here, may need to use delete() itself
                        getContext().deleteFile(key);
                    } else if (msgType.equals(QUERY)) {
                        String key = message[1];
                        String sourcePort = message[2];

                        FileInputStream inputStream = getContext().openFileInput(key);
                        Scanner s = new Scanner(inputStream);
                        String value = s.next();
                        Log.e("Sending to " + sourcePort, value);

                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, QUERY_RESPONSE, value, sourcePort);
                    } else if (msgType.equals(QUERY_RESPONSE)) {
                        String value = message[1];
                        qResponse.offer(value);
                    } else if (msgType.equals(QUERY_ALL)) {
                        String sourcePort = message[1];

                        //Get all local entries
                        Cursor curs = query(null, null, "@", null, null);
                        //Send them all one-by-one to the source
                        curs.moveToFirst();
                        String bigPacket = "";
                        for (int i = 0; i < curs.getCount(); i++) {
                            String key = curs.getString(0);
                            String value = curs.getString(1);

                            bigPacket = bigPacket + key + "." + value + ";";

                            curs.moveToNext();
                        }
                        bigPacket = bigPacket.substring(0, bigPacket.length() - 1);
                        Socket sock = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(sourcePort));
                        String msg = QUERY_ALL_RESPONSE + "," + bigPacket + "," + myPort + '\n';
                        DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                        out.writeBytes(msg);
                    } else if (msgType.equals(QUERY_ALL_RESPONSE)) {
                        String bigPacket = message[1];
                        String sourcePort = message[2];

                        String[] kvpairs = bigPacket.split(";");
                        for (int i = 0; i < kvpairs.length; i++) {
                            String[] kv = kvpairs[i].split("\\.");
                            String key = kv[0];
                            String value = kv[1];
                            globalDump.add(key + "," + value);
                        }
                    } else if (msgType.equals(REVIVAL_SUCC)) {
                        /*I've received notice of my predecessor's revival.
                          I must now send all key-value pairs that it should own to it.*/
                        String predPort = message[1];

                        //Get all local entries
                        Cursor curs = query(null, null, "@", null, null);

                        //Send them all one-by-one to your predecessor if they should belong to it
                        curs.moveToFirst();
                        String bigPacket = "";
                        for (int i = 0; i < curs.getCount(); i++) {
                            String key = curs.getString(0);
                            String value = curs.getString(1);
                            if (getOwner(key).equals(predPort)) {
                                bigPacket = bigPacket + key + "." + value + ";";
                            }
                            curs.moveToNext();
                        }
                        if (bigPacket.length() > 0) {
                            bigPacket = bigPacket.substring(0, bigPacket.length() - 1);
                            Socket sock = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(predPort));
                            String msg = REVIVAL_RESPONSE + "," + bigPacket + "," + myPort + '\n';
                            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                            out.writeBytes(msg);
                        }

                    } else if (msgType.equals(REVIVAL_PRED)) {
                        /*I've received notice of my successor's revival.
                          I must now send all key-value pairs that are owned by myself and my
                          predecessor to it as replicas. */

                        String succPort = message[1];
                        String predPort = getPred(myPort);

                        //Get all local entries
                        Cursor curs = query(null, null, "@", null, null);

                        //Send them all one-by-one to your successor if they are owned by yourself or your predecessor
                        curs.moveToFirst();
                        String bigPacket = "";
                        for (int i = 0; i < curs.getCount(); i++) {
                            String key = curs.getString(0);
                            String value = curs.getString(1);
                            if (getOwner(key).equals(predPort) || getOwner(key).equals(myPort)) {
                                bigPacket = bigPacket + key + "." + value + ";";
                            }
                            curs.moveToNext();
                        }
                        if (bigPacket.length() > 0) {
                            bigPacket = bigPacket.substring(0, bigPacket.length() - 1);
                            Socket sock = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(succPort));
                            String msg = REVIVAL_RESPONSE + "," + bigPacket + "," + myPort + '\n';
                            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                            out.writeBytes(msg);
                        }

                    } else if (msgType.equals(REVIVAL_RESPONSE)) {
                        String bigPacket = message[1];
                        String sourcePort = message[2];

                        String[] kvpairs = bigPacket.split(";");
                        for (int i = 0; i < kvpairs.length; i++) {
                            String[] kv = kvpairs[i].split("\\.");
                            String key = kv[0];
                            String value = kv[1];
                            storeKVP(key, value);
                        }
                    } else if (msgType.equals(INSERT_SUCCESS)) {
                        String sourcePort = message[1];
                        insertResponse.offer(sourcePort); //Arbitrary string. Might as well use sourcePort.
                    } else if (msgType.equals(REPLICATE_SUCCESS)) {
                        String sourcePort = message[1];
                        replicaResponse.offer(sourcePort); //Arbitrary string. Might as well use sourcePort.
                    } else if (msgType.equals(DELETE_SUCCESS)) {
                        String sourcePort = message[1];
                        deleteResponse.offer(sourcePort); //Arbitrary string. Might as well use sourcePort.
                    } else if (msgType.equals(DELETE_REPLICA_SUCCESS)) {
                        String sourcePort = message[1];
                        deleteReplicaResponse.offer(sourcePort); //Arbitrary string. Might as well use sourcePort.
                    } else if (msgType.equals(INSERT_COMPLETE)) {
                        String sourcePort = message[1];
                        insertCompleteResponse.offer(sourcePort);
                    } else if (msgType.equals(REPLICA2_SUCCESS)) {
                        String sourcePort = message[1];
                        replica2Response.offer(sourcePort);
                    } else {
                        throw new Error(msgType + " doesn't have a case in ServerTask");
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
