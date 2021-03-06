package org.pentaho.di.trans.consumer;

import com.alibaba.fastjson.JSONObject;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaStream;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.*;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.*;
import  org.I0Itec.zkclient.IZkStateListener;
/**
 * Kafka Consumer step processor
 *
 * @author Michael Spector
 */
public class KafkaConsumer extends BaseStep implements StepInterface {
    public static final String CONSUMER_TIMEOUT_KEY = "consumer.timeout.ms";

    private static Class<?> PKG = KafkaConsumerMeta.class; // for i18n purposes, needed by Translator2!!
    public KafkaConsumer(StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
                         Trans trans) {
        super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
    }

    public boolean init(StepMetaInterface smi, StepDataInterface sdi) {
        super.init(smi, sdi);

        KafkaConsumerMeta meta = (KafkaConsumerMeta) smi;
        KafkaConsumerData data = (KafkaConsumerData) sdi;

        Properties properties = meta.getKafkaProperties();
        Properties substProperties = new Properties();
        for (Entry<Object, Object> e : properties.entrySet()) {
            substProperties.put(e.getKey(), environmentSubstitute(e.getValue().toString()));
        }
        if (meta.isStopOnEmptyTopic()) {

            // If there isn't already a provided value, set a default of 1s
            if (!substProperties.containsKey(CONSUMER_TIMEOUT_KEY)) {
                substProperties.put(CONSUMER_TIMEOUT_KEY, "1000");
            }
        } else {
            if (substProperties.containsKey(CONSUMER_TIMEOUT_KEY)) {
                logError(BaseMessages.getString( PKG,"KafkaConsumer.WarnConsumerTimeout"));
            }
        }
        ConsumerConfig consumerConfig = new ConsumerConfig(substProperties);

        logBasic(BaseMessages.getString( PKG,"KafkaConsumer.CreateKafkaConsumer.Message", consumerConfig.zkConnect()));
        data.consumer = Consumer.createJavaConsumerConnector(consumerConfig);
        Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
        String topic = environmentSubstitute(meta.getTopic());
        topicCountMap.put(topic, 1);
        Map<String, List<KafkaStream<byte[], byte[]>>> streamsMap = data.consumer.createMessageStreams(topicCountMap);
        logDebug("Received streams map: " + streamsMap);
        data.streamIterator = streamsMap.get(topic).get(0).iterator();

        return true;
    }

    public void dispose(StepMetaInterface smi, StepDataInterface sdi) {
        KafkaConsumerData data = (KafkaConsumerData) sdi;
        if (data.consumer != null) {
            data.consumer.shutdown();

        }
        super.dispose(smi, sdi);
    }

    public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException {
        Object[] r = getRow();
        if (r == null) {
            /*
			 * If we have no input rows, make sure we at least run once to
			 * produce output rows. This allows us to consume without requiring
			 * an input step.
			 */
            if (!first) {
                setOutputDone();
                return false;
            }
            r = new Object[0];
        } else {
            incrementLinesRead();
        }

        final Object[] inputRow = r;

        final KafkaConsumerMeta meta = (KafkaConsumerMeta) smi;
        final KafkaConsumerData data = (KafkaConsumerData) sdi;

        if (first) {
            first = false;
            data.inputRowMeta = getInputRowMeta();
            // No input rows means we just dummy data
            if (data.inputRowMeta == null) {
                data.outputRowMeta = new RowMeta();
                data.inputRowMeta = new RowMeta();
            } else {
                data.outputRowMeta = getInputRowMeta().clone();
            }
            meta.getFields(data.outputRowMeta, getStepname(), null, null, this, null, null);
        }

        try {
            long timeout;
            String strData = meta.getTimeout();

            timeout = getTimeout(strData);

            logDebug("Starting message consumption with overall timeout of " + timeout + "ms");

            KafkaConsumerCallable kafkaConsumer = new KafkaConsumerCallable(meta, data, this) {
                protected void messageReceived(byte[] key, byte[] message) throws KettleException {
                    boolean flag = false;
                    if(meta.getDataformate()!=null&&!KafkaConsumerMeta.formateTye[0].equals(meta.getDataformate())&&!"".equals(meta.getDataformate())){
                        JSONObject jsonObject = null;
                        try {
                            jsonObject = JSONObject.parseObject(new String(message));
                        }catch (Exception e){
                            logError(e.getMessage());
                        }
                        Map<String,Integer> mapdata = meta.getMap();
                        int length = mapdata.size()+2;
                        Object[] realdata= new Object[length];
                        int i=0;
                        JSONObject tmpjson = null;
                        String tmpkey[] = null;
                        for(String jkey:mapdata.keySet()){
                            if(jkey.indexOf(KafkaConsumerMeta.MIDDLE.toString())!=-1){
                                tmpkey = jkey.split(KafkaConsumerMeta.MIDDLE.toString());
                                if(tmpkey!=null&&tmpkey.length==2){
                                    if(null ==jsonObject || null == jsonObject.get(tmpkey[0])){
                                        realdata[i] = null;
                                    }else {
                                        tmpjson = (JSONObject) jsonObject.get(tmpkey[0]);
                                        if (tmpjson != null) {
                                            if (tmpjson.get(tmpkey[1]) instanceof Integer) {
                                                realdata[i] = Integer.parseInt(tmpjson.get(tmpkey[1]).toString());
                                            }else if (tmpjson.get(tmpkey[1]) instanceof java.math.BigDecimal) {
                                                realdata[i] = (java.math.BigDecimal)tmpjson.get(tmpkey[1]);
                                            } else if(tmpjson.get(tmpkey[1]) instanceof String) {
                                                if (null == tmpjson.get(tmpkey[1])) {
                                                    realdata[i] = null;
                                                } else {
                                                    realdata[i] = tmpjson.get(tmpkey[1]).toString();
                                                }
                                            }else{
                                                realdata[i]=null;
                                            }
                                        } else {
                                            realdata[i] = null;
                                        }
                                    }
                                }else{
                                    realdata[i] = null;
                                }

                            }else{

                                if(jkey.equals("op_ts")){
                                    String opstime = jsonObject.get(jkey)==null?"":jsonObject.get("op_ts").toString();
                                    if(opstime.length()>23){
                                        opstime = opstime.substring(0,23);
                                    }
                                    realdata[i] = opstime;
                                }else {
                                    flag = jsonObject.get(jkey)==null?true:false;
                                    if(flag){
                                        realdata[i]=null;
                                    }else {
                                        if (jsonObject.get(jkey) instanceof Integer) {
                                            realdata[i] = Integer.parseInt(jsonObject.get(jkey).toString());
                                        } else if (jsonObject.get(jkey) instanceof String) {
                                            realdata[i] = jsonObject.get(jkey).toString();

                                        }
                                    }
                                }
                            }
                            i++;
                        }
                        realdata[length-2] = 1;
                        realdata[length-1] =new Timestamp(System.currentTimeMillis());
                        Object[] newRow = RowDataUtil.addRowData(inputRow.clone(), data.inputRowMeta.size(), realdata);
                        putRow(data.outputRowMeta, newRow);
                        if (isRowLevel()) {
                            logRowlevel(BaseMessages.getString( PKG,"KafkaConsumer.Log.OutputRow",
                                    Long.toString(getLinesWritten()), data.outputRowMeta.getString(newRow)));
                        }
                    }else{

                        Object[] newRow = RowDataUtil.addRowData(inputRow.clone(), data.inputRowMeta.size(),
                                new Object[]{message, key,1,new Timestamp(System.currentTimeMillis())});
                        putRow(data.outputRowMeta, newRow);

                        if (isRowLevel()) {
                            logRowlevel(BaseMessages.getString( PKG,"KafkaConsumer.Log.OutputRow",
                                    Long.toString(getLinesWritten()), data.outputRowMeta.getString(newRow)));
                        }
                    }
                }
            };
            if (timeout > 0) {
                logDebug("Starting timed consumption");
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<?> future = executor.submit(kafkaConsumer);
                    executeFuture(timeout, future);
                } finally {
                    executor.shutdown();
                }
            } else {
                logDebug("Starting direct consumption");
                kafkaConsumer.call();
            }
        } catch (KettleException e) {
            if (!getStepMeta().isDoingErrorHandling()) {
                logError(BaseMessages.getString( PKG,"KafkaConsumer.ErrorInStepRunning", e.getMessage()));
                setErrors(1);
                stopAll();
                setOutputDone();
                return false;
            }
            putError(getInputRowMeta(), r, 1, e.toString(), null, getStepname());
        }
        return true;
    }

    private void executeFuture(long timeout, Future<?> future) throws KettleException {
        try {
            future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logDebug("Timeout exception on the Future");
        } catch (Exception e) {
            throw new KettleException(e);
        }
    }

    private long getTimeout(String strData) throws KettleException {
        long timeout;
        try {
            timeout = KafkaConsumerMeta.isEmpty(strData) ? 0 : Long.parseLong(environmentSubstitute(strData));
        } catch (NumberFormatException e) {
            throw new KettleException("Unable to parse step timeout value", e);
        }
        return timeout;
    }

    public void stopRunning(StepMetaInterface smi, StepDataInterface sdi) throws KettleException {

        KafkaConsumerData data = (KafkaConsumerData) sdi;
        data.consumer.shutdown();
        data.canceled = true;

        super.stopRunning(smi, sdi);
    }
}
