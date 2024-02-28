package ch.epfl.gsn.wrappers.backlog.plugins;

import ch.epfl.gsn.beans.DataField;
import ch.epfl.gsn.beans.DataTypes;
import ch.epfl.gsn.beans.InputInfo;
import ch.epfl.gsn.wrappers.BackLogWrapper;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Properties;

import net.tinyos.message.SerialPacket;
import net.tinyos.packet.Serial;
import net.tinyos1x.message.TOSMsg;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * This plugin offers the functionality to read/send TinyOS messages by using
 * Mig generated java message classes. TinyOS1.x as well as TinyOS2.x
 * generated messages are supported.
 * <p>
 * If this class is used with 'unique-timestamps=false' (defined in the
 * virtual sensor's XML file) all message classes should implement at least
 * the following two functions:
 * 
 * <li>{@code int get_header_atime_low()} (or with different getter prefix as defined in the XML)</li>
 * <li>{@code short get_header_atime_high()} (or with different getter prefix as defined in the XML)</li>
 * <p>
 * Thus, the time the packet has been generated can be calculated and written to 
 * the SQL database.
 * 
 * @author Tonio Gsell
 */
public class MigMessagePlugin extends AbstractPlugin
{
	private int ACCESS_NODE_ID_BOUNDARY = 1024;
	
	
	private MigMessageParameters parameters = null;

	private Constructor<?> messageConstructor = null;
	private Constructor<?> voidMessageConstructor = null;

	private final transient Logger logger = LoggerFactory.getLogger( MigMessagePlugin.class );
	
	private DataField[] outputstructure = null;
	private String[] outputstructurenames;
	
	private final static int tinyos1x_groupId = -1;
	private String tinyos1x_platform = null;
	private MigMessageMultiplexer migMsgMultiplexer = null;

	private TOSMsg template;
	
	private int msgType;
	
	private static Hashtable<Class<?>,Method> parseMapping = null;

	@Override
	public boolean initialize(BackLogWrapper backlogwrapper, String coreStationName, String deploymentName) {
		super.activeBackLogWrapper = backlogwrapper;
		String p = getActiveAddressBean().getPredicateValue("priority");
		if (p == null)
			priority = null;
		else
			priority = Integer.valueOf(p);
		
		String propertyfile = "conf/permasense/" + deploymentName + "-migmessageplugin.properties";
		Properties props = new Properties(); 
		try {
			props.load(new FileInputStream(propertyfile));
		} catch (FileNotFoundException e) {
			logger.warn("migmessageplugin property file not available for " + deploymentName + " deployment");
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		}
		
		try {
			migMsgMultiplexer = MigMessageMultiplexer.getInstance(coreStationName, deploymentName, props, backlogwrapper.getBLMessageMultiplexer());
			
			// get the Mig message class for the specified TOS packet
			parameters = new MigMessageParameters();
			parameters.initParameters(getActiveAddressBean());
			Class<?> classTemplate = Class.forName(parameters.getTinyosMessageName());
			parameters.buildOutputStructure(classTemplate, new ArrayList<DataField>(), new ArrayList<Method>(),  new ArrayList<Method>());
			messageConstructor = classTemplate.getConstructor(byte[].class) ;
			voidMessageConstructor = classTemplate.getConstructor() ;
			
			// if it is a TinyOS1.x message class we need the platform name
			if (parameters.getTinyosVersion() == MigMessageParameters.TINYOS_VERSION_1) {
				tinyos1x_platform = props.getProperty(MigMessageMultiplexer.TINYOS1X_PLATFORM);
				msgType = ((net.tinyos1x.message.Message) messageConstructor.newInstance(new byte [1])).amType();

				// a template message for this platform has to be instantiated to be able to get the data offset
				// if a message has to be sent to the deployment
			   	Class<?> msgCls = Class.forName ( "net.tinyos1x.message." + tinyos1x_platform + ".TOSMsg" ) ;
			   	Constructor<?> c = msgCls.getConstructor () ;
				template = (TOSMsg) c.newInstance () ;
			}
			else {
				msgType = ((net.tinyos.message.Message) messageConstructor.newInstance(new byte [1])).amType();
			}
			
			migMsgMultiplexer.registerListener(msgType, this);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return false;
		}
		
		return true;
	}


	@Override
	public String getPluginName() {
		return "MigMessagePlugin";
	}

	
	@Override
	public boolean messageReceived(int deviceId, long timestamp, Serializable[] data) {
		Method getter = null;
		Object res = null;

		LinkedHashMap<String, Serializable> outputvaluesmap = 
			new LinkedHashMap<String, Serializable>(outputstructure.length);
		
		outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[0], timestamp);
		outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[1], null);
		outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[2], null);
		
		try {
			Object msg = (Object) messageConstructor.newInstance((byte[])data[0]);

			Iterator<Method> iter = parameters.getGetters().iterator();
			while (iter.hasNext()) {
				getter = iter.next();
				getter.setAccessible(true);
				res = getter.invoke(msg);
				if (getter.getReturnType().isArray()) {
					String name = getter.getName().toLowerCase();
					for (int i=0; i < Array.getLength(res); i++) {
						//outputvaluesmap.put(name + "[" + i + "]", (Serializable) Array.get(res, i));
						addValue(outputvaluesmap, name + "[" + i + "]", (Serializable) Array.get(res, i));
					}
				}
				else {
					//outputvaluesmap.put(getter.getName().toLowerCase(), (Serializable) res);
					addValue(outputvaluesmap, getter.getName().toLowerCase(), (Serializable) res);
				}
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return false;
		}
		
		// special merge of "*_low" and "*_high" fields
		for (int i=outputvaluesmap.size(); i < outputstructure.length; i++) {
			String key = parameters.getTinyosGetterPrefix() + outputstructurenames[i];
			int merged = 0;
			if (outputvaluesmap.containsKey(key + "_low"))
				merged += ((Integer) outputvaluesmap.get(key + "_low")).intValue();
			if (outputvaluesmap.containsKey(key + "_high"))
				merged += ((Short) outputvaluesmap.get(key + "_high")).intValue() << 16;
			outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[i], merged);
		}
		
		if (outputvaluesmap.containsKey(parameters.getTinyosGetterPrefix() + "header_atime")) {
			long atime = ((Integer) outputvaluesmap.get(parameters.getTinyosGetterPrefix() + "header_atime")).longValue();
			outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[1], timestamp - (atime * 1000));
		} else {
			outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[1], timestamp);
		}
		
		if (outputvaluesmap.containsKey(parameters.getTinyosGetterPrefix() + "header_originatorid")) {
			Integer id = ((Integer) outputvaluesmap.get(parameters.getTinyosGetterPrefix() + "header_originatorid")).intValue();
			outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[2], id);
		} else {
			outputvaluesmap.put(parameters.getTinyosGetterPrefix() + outputstructurenames[2], deviceId);
		}
		
		if (dataProcessed(System.currentTimeMillis(), outputvaluesmap.values().toArray(new Serializable[] {})))
			ackMessage(timestamp, super.priority);
		else
			logger.warn("The message with timestamp >" + timestamp + "< could not be stored in the database.");

		return true;
	}

	
	@Override
	public DataField[] getOutputFormat() {
		if (outputstructure == null) {
			String s;
			LinkedHashMap<String, DataField> outputstructuremap = 
				new LinkedHashMap<String, DataField>(3 + parameters.getOutputStructure().length);
			outputstructuremap.put("timestamp", new DataField("timestamp", DataTypes.BIGINT));
			outputstructuremap.put("generation_time", new DataField("generation_time", DataTypes.BIGINT));
			outputstructuremap.put("device_id", new DataField("device_id", DataTypes.INTEGER));
			
			for(int i=0; i<parameters.getOutputStructure().length; i++) {
				outputstructuremap.put(parameters.getOutputStructure()[i].getName(), 
						parameters.getOutputStructure()[i]);
			}

			// special merge of "*_low" and "*_high" fields
			outputstructurenames = outputstructuremap.keySet().toArray(new String[] {});
			for (int i=0; i < outputstructurenames.length; i++) {
				s = outputstructurenames[i];
				if (s.endsWith("_low") || s.endsWith("_high")) {
					s = s.substring(0, s.lastIndexOf('_')).toLowerCase();
					if (!outputstructuremap.containsKey(s))
						outputstructuremap.put(s, new DataField(s, DataTypes.INTEGER));
				}					
			}

			outputstructurenames = outputstructuremap.keySet().toArray(new String[] {});
			outputstructure = outputstructuremap.values().toArray(new DataField[] {});
		}
		return outputstructure;
	}

	
	@Override
	public short getMessageType() {
		if (parameters.getTinyosVersion() == MigMessageParameters.TINYOS_VERSION_1)
			return ch.epfl.gsn.wrappers.backlog.BackLogMessage.TOS1x_MESSAGE_TYPE;
		else
			return ch.epfl.gsn.wrappers.backlog.BackLogMessage.TOS_MESSAGE_TYPE;
	}
	
	
	@Override
	public void dispose() {
		migMsgMultiplexer.deregisterListener(msgType, this);
	}



	@Override
	public InputInfo sendToPlugin(String action, String[] paramNames, Object[] paramValues) {
		if (getDeviceID() != null && getDeviceID() <= ACCESS_NODE_ID_BOUNDARY) {
			boolean ret = false;
			if (logger.isDebugEnabled())
				logger.debug("action: " + action);
			if( action.compareToIgnoreCase("payload") == 0 ) {
				int moteId = -257;
				int amType = -257;
				byte[] data = null;
				
				if( paramNames.length != 3 ) {
					logger.error("upload action must have three parameter names: 'destination', 'amtype' and 'data'");
					return new InputInfo(getActiveAddressBean().toString(), "upload action must have three parameter names: 'destination', 'amtype' and 'data'", false);
				}
				if( paramValues.length != 3 ) {
					logger.error("upload action must have three parameter values");
					return new InputInfo(getActiveAddressBean().toString(), "upload action must have three parameter values", false);
				}
				
				for( int i=0; i<3; i++ ) {
					try {
						String tmp = paramNames[i];
						if( tmp.compareToIgnoreCase("destination") == 0 )
							moteId = Integer.parseInt((String) paramValues[i]);
						else if( tmp.compareToIgnoreCase("am_type") == 0 )
							amType = Integer.parseInt((String) paramValues[i]);
						else if( tmp.compareToIgnoreCase("data") == 0 )
							data = ((String) paramValues[i]).getBytes();
					} catch(Exception e) {
						logger.error("Could not interprete upload arguments: " + e.getMessage());
						return new InputInfo(getActiveAddressBean().toString(), "Could not interprete upload arguments: " + e.getMessage(), false);
					}
				}
				
				if( moteId < -256 | amType < -256 | data == null ) {
					logger.error("upload action must contain all three parameter names: 'destination', 'am type' and 'data'");
					return new InputInfo(getActiveAddressBean().toString(), "upload action must contain all three parameter names: 'destination', 'am type' and 'data'", false);
				}
				
				if(data.length == 0) {
					logger.warn("Upload message's data field is empty");
				}
				
				try {
					ret = sendRemote(System.currentTimeMillis(), new Serializable[] {createTOSpacket(moteId, amType, data)}, super.priority);
					if (logger.isDebugEnabled())
						logger.debug("Mig message sent to destination " + moteId + " with AM type " + amType);
				} catch (IOException e) {
					logger.warn(e.getMessage());
				}
			}
			else if( action.compareToIgnoreCase("binary_packet") == 0 ) {
				if(((String)paramNames[0]).compareToIgnoreCase("binary packet") == 0) {
					byte [] packet = ((String) paramValues[0]).getBytes();
					if(packet.length > 0) {
						try {
							ret = sendRemote(System.currentTimeMillis(), new Serializable[] {packet}, super.priority);
							if (logger.isDebugEnabled())
								logger.debug("Mig binary message sent with length " + ((String) paramValues[0]).length());
						} catch (IOException e) {
							logger.warn(e.getMessage());
						}
					}
					else {
						logger.error("Upload failed due to empty 'binary packet' field");
					}
				}
				else {
					logger.error("binary_packet upload action needs a 'binary packet' field.");
				}
			}
			else if( action.compareToIgnoreCase("tosmsg") == 0 ) {
				// compose tos packet for sending
				Method setter = null;
				
				if (parseMapping==null) {
					try {
						buildMappings();
					}
					catch (Exception e) {
						logger.error(e.getMessage());
						return new InputInfo(getActiveAddressBean().toString(), e.getMessage(), false);
					}
				}
					
				try {
					Object msg = (Object) voidMessageConstructor.newInstance();
	
					Iterator<Method> iter = parameters.getSetters().iterator();
					while (iter.hasNext()) {
						setter = iter.next();
						setter.setAccessible(true);
						for (int i=0; i<paramNames.length;i++) {
							String name = setter.getName();
							if (paramNames[i].compareToIgnoreCase(name.substring(parameters.getTinyosSetterPrefix().length()))==0) {
								Class<?>[] setterparams = setter.getParameterTypes();
								if (setterparams.length==1) {
									Class<?> param = setterparams[0];
									if (logger.isDebugEnabled())
										logger.debug("set field "+name+" to "+ (String)paramValues[i]);
									Method parser = parseMapping.get(param);
									setter.invoke(msg, parser.invoke(null, paramValues[i]));
								}
								else {
									logger.warn("Unknown setter with "+ setterparams.length +" parameters");
								}	
								break;
							}
						}
					}
					if (logger.isDebugEnabled())
						logger.debug(msg.toString());
					// switch tos version
					byte[] packet;
					if (tinyos1x_platform != null) {
						net.tinyos1x.message.Message tosmsg = (net.tinyos1x.message.Message)msg;
						packet = createTOSpacket(0xffff, tosmsg.amType(), tosmsg.dataGet());
					}
					else {
						net.tinyos.message.Message tosmsg = (net.tinyos.message.Message)msg;
						packet = createTOSpacket(0xffff, tosmsg.amType(), tosmsg.dataGet());
					}
					try {
						ret = sendRemote(System.currentTimeMillis(), new Serializable[] {packet}, super.priority);
					} catch (IOException e) {
						logger.warn(e.getMessage());
					}
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
					return new InputInfo(getActiveAddressBean().toString(), e.getMessage(), false);
				}
			}
			else
				logger.error("Unknown action");
			
			if (ret)
				return new InputInfo(getActiveAddressBean().toString(), "MIG message upload successfull", ret);
			else
				return new InputInfo(getActiveAddressBean().toString(), "MIG message upload not successfull", ret);
		}
		else {
			if (getDeviceID() != null)
				logger.debug("device ID (" + getDeviceID() + ") bigger than " + ACCESS_NODE_ID_BOUNDARY);
			return new InputInfo(getActiveAddressBean().toString(), "device ID (" + getDeviceID() + ") bigger than " + ACCESS_NODE_ID_BOUNDARY, false);
		}
	}

	private static void buildMappings () throws SecurityException, NoSuchMethodException {
		parseMapping = new Hashtable<Class<?>, Method> () ;
		parseMapping.put(byte.class, Byte.class.getMethod("parseByte", String.class)) ;
		parseMapping.put(short.class, Short.class.getMethod("parseShort", String.class)) ;
		parseMapping.put(int.class, Integer.class.getMethod("parseInt", String.class)) ;
		parseMapping.put(long.class, Long.class.getMethod("parseLong", String.class)) ;
		parseMapping.put(float.class, Float.class.getMethod("parseFloat", String.class));
		parseMapping.put(double.class, Double.class.getMethod("parseDouble", String.class));
	}

    private byte[] createTOSpacket(int moteId, int amType, byte[] data) throws IOException {
		if (amType < 0) {
		    throw new IOException("unknown AM type for message");
		}
	
		// which TinyOS messages version are we generating?
		if (tinyos1x_platform != null) {
			// the following functionality has been extracted from net.tinyos1x.message.Sender
			// from the send function
			TOSMsg packet ;
		      
			// normal case, a PhoenixSource
			// hack: we don't leave any space for the crc, so
			// numElements_data() will be wrong. But we access the
			// data area via dataSet/dataGet, so we're ok.
			packet = createTOSMsg ( template.offset_data ( 0 ) + data.length ) ;

			// message header: destination, group id, and message type
			packet.set_addr ( moteId ) ;
			packet.set_group ( (short) tinyos1x_groupId ) ;
			packet.set_type ( ( short ) amType ) ;
			packet.set_length ( ( short ) data.length ) ;
		      
			packet.dataSet ( data , 0 , packet.offset_data ( 0 ) , data.length ) ;

			return packet.dataGet();
		}
		else {
			// the following functionality has been extracted from net.tinyos.message.Sender
			// from the send function
			SerialPacket packet =
			    new SerialPacket(SerialPacket.offset_data(0) + data.length);
			packet.set_header_dest(moteId);
			packet.set_header_type((short)amType);
			packet.set_header_length((short)data.length);
			packet.dataSet(data, 0, SerialPacket.offset_data(0), data.length);
		
			byte[] packetData = packet.dataGet();
			byte[] fullPacket = new byte[packetData.length + 1];
			fullPacket[0] = Serial.TOS_SERIAL_ACTIVE_MESSAGE_ID;
			System.arraycopy(packetData, 0, fullPacket, 1, packetData.length);
			return fullPacket;
		}
    }
	
//	
// following functions are only used for TinyOS1.x messages	
//
	
	TOSMsg instantiateTOSMsg ( Class<?> [] cArgs , Object [] args ) {
	   	try {
		   	Class<?> msgCls ;
         
		   	msgCls = Class.forName ( "net.tinyos1x.message." + tinyos1x_platform + ".TOSMsg" ) ;
         
		   	Constructor<?> c = msgCls.getConstructor ( cArgs ) ;
		   	return (TOSMsg) c.newInstance ( args ) ;
	   	}
	   	catch ( ClassNotFoundException e ) {
		   	System.err.println ( "Could not find a platform specific version of TOSMsg" ) ;
		   	System.err.println ( e ) ;
		   	e.printStackTrace () ;
	   	}
	   	catch ( NoSuchMethodException e ) {
		   	System.err.println ( "Could not locate the appropriate constructor; check the class " + "net.tinyos1x.message." + tinyos1x_platform
                              + ".TOSMsg" ) ;
		   	e.printStackTrace () ;
	   	}
	   	catch ( InstantiationException e ) {
		   	System.err.println ( "Could not instantiate the class: " + e ) ;
		   	e.printStackTrace () ;
      	}
      	catch ( IllegalAccessException e ) {
    	  	System.err.println ( "Illegal access: " + e ) ;
         	e.printStackTrace () ;
      	}
      	catch ( InvocationTargetException e ) {
      		System.err.println ( "Reflection problems: " + e ) ;
         	e.printStackTrace () ;
      	}
      	return null ;
   	}

    public TOSMsg createTOSMsg ( int data_length ) {
    	Object [] initArgs = new Object [ 1 ] ;
    	Class<?> [] cArgs = new Class [ 1 ] ;
    	cArgs [ 0 ] = Integer.TYPE ;
    	initArgs [ 0 ] = new Integer ( data_length ) ;
    	
    	return instantiateTOSMsg ( cArgs , initArgs ) ;
    }

	
	
	private void addValue(LinkedHashMap<String, Serializable> map, String name, Serializable obj) {
		// convert Float/Double NaN to null
		if (obj != null) {
			if (obj.getClass().isAssignableFrom(Float.class)) {
				if (((Float) obj).isNaN())
					obj = null;
			} else if (obj.getClass().isAssignableFrom(Double.class)) {
				if (((Double) obj).isNaN())
					obj = null;
			}
		}
		map.put(name, obj);
	}
}
