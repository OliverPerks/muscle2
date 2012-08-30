package muscle.core.standalone;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;
import muscle.util.concurrency.Disposable;
import muscle.util.data.SerializableData;
import muscle.util.serialization.DeserializerWrapper;
import muscle.util.serialization.SerializerWrapper;
import muscle.util.serialization.Xdr;
import muscle.util.serialization.XdrDeserializerWrapper;
import muscle.util.serialization.XdrNIODeserializerWrapper;
import muscle.util.serialization.XdrNIOSerializerWrapper;
import muscle.util.serialization.XdrSerializerWrapper;
import org.acplt.oncrpc.XdrTcpDecodingStream;
import org.acplt.oncrpc.XdrTcpEncodingStream;


public class NativeGateway extends Thread implements Disposable {
	protected final ServerSocket ss;
	protected final CallListener listener;
	protected static final Logger logger = Logger.getLogger(NativeGateway.class.getName());
	private final static boolean USE_ASYNC = System.getProperty("muscle.core.standalone.use_async") == null ? true : Boolean.parseBoolean(System.getProperty("muscle.core.standalone.use_async"));
	private volatile boolean isDone;
	private boolean isDisposed;
	
	public NativeGateway(CallListener listener) throws UnknownHostException, IOException {
		super("NativeGateway-" + listener.getKernelName());
		
		if (USE_ASYNC) {
			ServerSocketChannel ssc = ServerSocketChannel.open();
			ss = ssc.socket();
			ss.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{ 127, 0, 0, 1}), 0), 1);
		} else {
			ss = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{ 127, 0, 0, 1}));		
		}
		
		this.listener = listener;
		
		setDaemon(true);
	}

	@Override
	public void dispose() {
		this.isDone = true;
		try {
			// The only way to abort an accept method
			ss.close();
		} catch (IOException ex) {
			Logger.getLogger(NativeGateway.class.getName()).log(Level.SEVERE, listener.getKernelName() + " could not close connection with native code.", ex);
		}
	}

	@Override
	public boolean isDisposed() {
		return this.isDone;
	}
	
	public interface CallListener {
		/* OPCODE = 0 */
		public void isFinished();
		/* OPCODE = 1 */
		public String getKernelName();
		/* OPCODE = 2 */
		public String getProperty(String name);
		public boolean hasProperty(String name);
		/* OPCODE = 3 */
		public boolean willStop();
		/* OPCODE = 4 */
		public void send(String entranceName, SerializableData data);
		/* OPCODE = 5 */
		public SerializableData receive(String exitName);
		/* OPCODE = 6 */
		public String getProperties();
		/* OPCODE = 7 */
		public String getTmpPath();
		/* OPCODE = 8 */
		public boolean hasNext(String exitName);
			
		}

	public int getPort() {
		return ss.getLocalPort();
		
		//return "tcp://" + ss.getInetAddress() + ":" + ss.getLocalPort();
	}
	public InetAddress getInetAddress() {
		return ss.getInetAddress();
	
		//return "tcp://" + ss.getInetAddress() + ":" + ss.getLocalPort();
	}
	
	@Override
	public void run() {
		Socket s = null;
		DeserializerWrapper in = null;
		SerializerWrapper out = null;
		try {
			if (USE_ASYNC) {
				SocketChannel sc = ss.getChannel().accept();
				sc.configureBlocking(false);
				s = sc.socket();

				int buffer_size = XdrNIOSerializerWrapper.DEFAULT_BUFFER_SIZE;
				in =  new XdrNIODeserializerWrapper(new Xdr(sc, buffer_size));
				out = new XdrNIOSerializerWrapper(new Xdr(sc, buffer_size), buffer_size);
			} else {
				s = ss.accept();

				int buffer_size = XdrSerializerWrapper.DEFAULT_BUFFER_SIZE;
				in =  new XdrDeserializerWrapper(new XdrTcpDecodingStream(s, buffer_size));
				out = new XdrSerializerWrapper(new XdrTcpEncodingStream(s, buffer_size), buffer_size);
			}
			
			logger.log(Level.FINE, "Accepted connection from: {0}:{1}", new Object[]{s.getRemoteSocketAddress(), s.getPort()});
			final boolean isFinestLog = logger.isLoggable(Level.FINEST);
			while (!isDisposed) {
				if (isFinestLog) logger.finest("Starting decoding...");
				in.refresh();

				int operationCode = in.readInt();
				if (isFinestLog) logger.log(Level.FINEST, "Operation code = {0}", operationCode);
				
				switch (operationCode) {
					case 0:
					{
						if (isFinestLog) logger.finest("finalize() request.");
						in.close();
						out.close();
						listener.isFinished();
						if (isFinestLog) logger.finest("Native Process Gateway exiting...");
						return;
					}	
					case 1:
					{
						if (isFinestLog) logger.finest("getKernelName() request.");
						out.writeString(listener.getKernelName());
						if (isFinestLog) logger.log(Level.FINEST, "Kernel name sent : {0}", listener.getKernelName());
						break;
					}
					case 2:
					{
						if (isFinestLog) logger.finest("getProperty() request.");
						String name = in.readString();
						if (listener.hasProperty(name)) {
							String value = listener.getProperty(name);
							if (isFinestLog) logger.log(Level.FINEST, "Property value read: {0}", value);
							out.writeBoolean(true);
							out.writeString(value);
							if (isFinestLog) logger.log(Level.FINEST, "Property value sent: {0}", value);
						} else {
							if (isFinestLog) logger.log(Level.WARNING, "Property ''{0}'' for instance {1} does not exist", new Object[] {name, listener.getKernelName()});
							out.writeBoolean(false);
						}
						break;
					}
					case 3:
					{
						if (isFinestLog) logger.finest("willStop() request.");
						boolean stop = listener.willStop();
						out.writeBoolean(stop);
						if (isFinestLog) logger.log(Level.FINEST, "Stop?: {0}", stop);
						break;
					}
					case 4:
					{
						if (isFinestLog) logger.finest("send() request.");
						String entranceName = in.readString();
						SerializableData data = SerializableData.parseData(in);
						if (isFinestLog) logger.log(Level.FINEST, "entranceName = {0}, data = {1}", new Object[]{entranceName, data});
						listener.send(entranceName, data);
						if (isFinestLog) logger.finest("data sent");
						break;
					}
					case 5:
					{
						if (isFinestLog) logger.finest("receive() request.");
						String exitName = in.readString();
						if (isFinestLog) logger.log(Level.FINEST, "exitName = {0}", exitName);
						SerializableData data =  listener.receive(exitName);
						if (data == null) {
							logger.log(Level.FINE, "Conduit {0} is disconnected; passing on signal to native code.", exitName);
							out.writeInt(-1);
						}
						else {
							if (isFinestLog) logger.log(Level.FINEST, "exitName = {0}, data = {1}", new Object[]{exitName, data});
							data.encodeData(out);
						}
						if (isFinestLog) logger.finest("data encoded");
						break;
					}
					case 6:
					{
						if (isFinestLog) logger.finest("getProperties() request.");
						out.writeString(listener.getProperties());
						break;
					}
					case 7:
					{
						if (isFinestLog) logger.finest("getTmpPath() request.");
						out.writeString(listener.getTmpPath());
						break;
					}
					case 8:
					{
						if (isFinestLog) logger.finest("hasNext() request.");
						String exit = in.readString();
						if (isFinestLog) logger.log(Level.FINEST, "hasNext({0}) request.", exit);
						boolean hasNext = listener.hasNext(exit);
						out.writeBoolean(hasNext);
						if (isFinestLog) logger.log(Level.FINEST, "hasNext({0}) = {1}", new Object[] {exit, hasNext});
						break;
					}
					default:
						throw new IOException("Unknown operation code " + operationCode);	
				}
				if (isFinestLog) logger.finest("flushing response");
				out.flush();
				
				if (isFinestLog) logger.finest("proceeding to next native call");
				in.cleanUp();
			}
		} catch (SocketException ex) {
			logger.log(Level.SEVERE, "Connection of " + listener.getKernelName() + " failed; most likely the native code exited.", ex);
		} catch (IOException ex) {
			logger.log(Level.SEVERE, "Communication error with " + listener.getKernelName(), ex);
		} catch (Throwable ex) {
			logger.log(Level.SEVERE, listener.getKernelName() + " could not finish communication with native code.", ex);
		} finally {
			listener.isFinished();
			if (s != null) {
				if (in != null) {
					try {
						in.close();
					} catch (Exception ex1) {
						Logger.getLogger(NativeGateway.class.getName()).log(Level.SEVERE, listener.getKernelName() + "could not close communications with native code", ex1);
					}
				}
				if (out != null) {
					try {
						out.close();
					} catch (Exception ex1) {
						Logger.getLogger(NativeGateway.class.getName()).log(Level.SEVERE, listener.getKernelName() + "could not close communications with native code", ex1);
					}
				}
				try {
					s.close();
				} catch (IOException ex1) {
					Logger.getLogger(NativeGateway.class.getName()).log(Level.SEVERE, listener.getKernelName() + "could not close communications with native code", ex1);
				}
			}
		}
	}
}
