package com.mdp.producer;

//java
import java.io.*;
import java.util.*;
import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.LinkOption.*;

//topic
// import kafka.admin.AdminUtils;
// import kafka.utils.ZKStringSerializer$;
// import kafka.utils.ZkUtils;
// import org.I0Itec.zkclient.ZkClient;
// import org.I0Itec.zkclient.ZkConnection;
// import kafka.common.TopicExistsException;

// //producer
// import kafka.javaapi.producer.Producer;
// import kafka.producer.ProducerConfig;
// import kafka.producer.KeyedMessage;
// import scala.collection.JavaConversions;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.security.JaasUtils;
import org.apache.kafka.common.utils.Utils;

import org.apache.kafka.common.serialization.StringSerializer;


import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;

import kafka.admin.AdminUtils;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;

//Kafka
import com.mdp.producer.JsonToString;
import com.mdp.producer.ExtractCSV;

public class WatchDir {

    private final WatchService watcher;
    private final Map<WatchKey,Path> keys;
    private boolean trace = false;

    // Kafka 
    private Properties props;
    private ProducerConfig config;
    private static KafkaProducer<String, String> producer;

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>)event;
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE/*, ENTRY_DELETE, ENTRY_MODIFY*/);
        if (trace) {
            Path prev = keys.get(key);
            if (prev == null) {
                System.out.format("register: %s\n", dir);
            } 
            else {
                if (!dir.equals(prev)) {
                    System.out.format("update: %s -> %s\n", prev, dir);
                }
            }
        }
        keys.put(key, dir);
    }

    public WatchDir(Path dir) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey,Path>();

        register(dir);

        // enable trace after initial registration
        this.trace = true;

        // Kafka 
        this.props = new Properties();

        //PRODUCTION 
        this.props.put("bootstrap.servers", "migsae-kafka.aura.arc-ts.umich.edu:9092");
        this.props.put("acks", "all");
        this.props.put("metadata.broker.list", "migsae-kafka.aura.arc-ts.umich.edu:9092");
        this.props.put("serializer.class", "kafka.serializer.StringEncoder");
        this.props.put("request.required.acks", "1");
        this.props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        this.props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        //LOCAL
        // this.props.put("bootstrap.servers", "localhost:9092");
        // this.props.put("acks", "all");
        // this.props.put("metadata.broker.list", "localhost:9092");    
        // this.props.put("serializer.class", "kafka.serializer.StringEncoder");
        // this.props.put("request.required.acks", "1");
        
        // this.config = new ProducerConfig(props);
        this.producer = new KafkaProducer<String, String>(props);
    }

    void processEvents() {
        while(true) {

            // wait for key to be signalled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                System.err.println("WatchKey not recognized!!");
                continue;
            }

            for (WatchEvent<?> event: key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();

                // TBD - provide example of how OVERFLOW event is handled
                if (kind == OVERFLOW) {
                    continue;
                }

                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);

                // print out event
                //System.out.format("%s: %s\n", event.kind().name(), child);
                (new Thread(new HandleEvent(child))).start();
                // (new HandleEvent(child)).run();
            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }

    // Kafka 
    public static void  new_topic(String topic){
        try{
            //used to create topic

            //PRODUCTION
            // ZkClient zkClient = new ZkClient("migsae-kafka.aura.arc-ts.umich.edu:2181/kafka", 10000, 10000, ZKStringSerializer$.MODULE$);

            //LOCAL
            // ZkClient zkClient = new ZkClient("localhost:2181", 10000, 10000, ZKStringSerializer$.MODULE$);

            String zookeeperConnect = "migsae-kafka.aura.arc-ts.umich.edu:2181/kafka";
            int sessionTimeoutMs = 10000;
            int connectionTimeoutMs = 10000;

            ZkClient zkClient = new ZkClient(zookeeperConnect,sessionTimeoutMs,connectionTimeoutMs,ZKStringSerializer$.MODULE$);

            boolean isSecureKafkaCluster = false;
            ZkUtils zkUtils = new ZkUtils(zkClient, new ZkConnection(zookeeperConnect), isSecureKafkaCluster);

            int partitions = 3;
            int replication = 1;

            // Add topic configuration here
            Properties topicConfig = new Properties();

            AdminUtils.createTopic(zkUtils, topic, partitions, replication, topicConfig);
        }
        catch (Exception e){
            System.out.println("Topic exists");
        }
    }

    public static void main(String[] args) throws IOException {
        // Kafka 
        String topic = "test2";
        String group_id = "report";
        new_topic(topic);
        //PRODUCTION
        String path = "C:\\Rockwell Automation\\WorkingDirectory";
        Path dir = Paths.get(path.replace("\\", "/"));
        //LOCAL
        // Path dir = Paths.get("/Users/stevenlengieza/Documents/college/Research/MDP-Cloud-Winter-2017/data");
        WatchDir watchDir = new WatchDir(dir);
        //send files that are already there
        File folder = new File(dir.toString()); 
        File[] listOfFiles = folder.listFiles();
        for (File file : listOfFiles) {
            if(file.toString().toLowerCase().endsWith(".dat")){
                sendTestBedData(file);
            }
            else if (file.toString().toLowerCase().endsWith(".csv")){
                sendSimulationData(file);
            }
        }

        watchDir.processEvents();
    }

    public class HandleEvent implements Runnable {
        private File file;

        public HandleEvent(Path file){
            this.file = new File(file.toString());
        }

        public void run(){
            if(file.toString().toLowerCase().endsWith(".dat")){
                // Kafka 
                //System.out.println("Getting kafkaMessages");
                HashMap<Long, List<String>> kafkaMessages = JsonToString.GetKafkaMessage(file);
                Set set = kafkaMessages.entrySet();
                // System.out.println("Kafka Message: " + set.toString());
                Iterator iterator = set.iterator();
                while(iterator.hasNext()) {
                    Map.Entry mentry = (Map.Entry)iterator.next();
                    // System.out.println("Sending data for Timestamp " + mentry.getKey());
                    //System.out.println("Timestamp: " + mentry.getKey() + " data " + (Arrays.toString(((List)mentry.getValue()).toArray())));
                    String dataList = "TestBed\t";
                    Iterator it = ((List)mentry.getValue()).iterator();
                    while(it.hasNext()){
                        dataList += it.next().toString() + "\t";
                    }
                    ProducerRecord<String, String> data = new ProducerRecord<String, String>("test1", dataList);
                    System.out.println("Sending Message: " + dataList);
                    producer.send(data);
                }
                file.delete();
                return;
            }
            else if(file.toString().toLowerCase().endsWith(".csv")){
                
                try{ //TODO move to simulation folder
                    FileInputStream fis = new FileInputStream(file);
                    ExtractCSV eofcsv = new ExtractCSV(fis);
                    String[][] data = eofcsv.extract();
                    for(int i = 0; i < data.length; ++i){
                        String message_data = "Simulation\t";
                        for(int j = 0; j < data[0].length; ++j){
                            message_data += data[i][j] + "\t";
                        }
                        ProducerRecord<String, String> message = new ProducerRecord<String, String>("test1", message_data);
                        System.out.println(message_data);
                        //producer.send(message);
                    }
                    file.delete();
                }
                catch (FileNotFoundException e) {
                    System.out.println("Something broke");
                }
                return;
            }
            else{
                System.out.println("File " + this.file.getName() + " does not end with .dat or .csv");
                return;
            }
        }
    }

    public static void sendTestBedData(File file){
        try{
            HashMap<Long, List<String>> kafkaMessages = JsonToString.GetKafkaMessage(file);
            Set set = kafkaMessages.entrySet();
            Iterator iterator = set.iterator();
            while(iterator.hasNext()){
                Map.Entry mentry = (Map.Entry)iterator.next();
                String dataList= "TestBed\t";
                Iterator it = ((List)mentry.getValue()).iterator();
                while(it.hasNext()){
                    dataList += it.next() + "\t";
                }
                ProducerRecord<String, String> data = new ProducerRecord<String, String>("test1", dataList);
                try {
                    System.out.println("Sending Message: " + dataList);
                    producer.send(data);
                }
                catch (Exception e){
                    System.out.println("Sending message failed with error message: " + e.getMessage());
                    e.printStackTrace(System.out);
                    return;
                }
            }
            file.delete();
        }
        catch (Exception e){
            System.out.println("Getting kafkaMessage failed with error message: " + e.getMessage());
            e.printStackTrace(System.out);
        }
        return;
    }

    public static void sendSimulationData(File file) {//TODO move to simulation folder
        try{
            FileInputStream fis = new FileInputStream(file);
            ExtractCSV eofcsv = new ExtractCSV(fis);
            String[][] data = eofcsv.extract();
            for(int i = 0; i < data.length; ++i){
                String message_data = "Simulation\t";
                for(int j = 0; j < data[0].length; ++j){
                    message_data += data[i][j] + "\t";
                }
                ProducerRecord<String, String> message = new ProducerRecord<String, String>("test1", message_data);
                System.out.println(message_data);
                //producer.send(message);
            }
            file.delete();
        }
        catch (FileNotFoundException e) {
            System.out.println("Something broke");
        }
    }
}