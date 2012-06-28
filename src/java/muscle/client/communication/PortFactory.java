/*
 * 
 */
package muscle.client.communication;

import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import muscle.client.communication.message.IncomingMessageProcessor;
import muscle.client.communication.Receiver;
import muscle.client.communication.Transmitter;
import muscle.client.instance.ConduitEntranceControllerImpl;
import muscle.client.instance.ConduitExitControllerImpl;
import muscle.core.ident.PortalID;
import muscle.core.ident.ResolverFactory;
import muscle.core.kernel.InstanceController;

/**
 * Assigns Receivers and Transmitters to Portals.
 * 
 * @author Joris Borgdorff
 */
public abstract class PortFactory {
	protected final ExecutorService executor;
	protected final ResolverFactory resolverFactory;
	protected final IncomingMessageProcessor messageProcessor;
	private final static Logger logger = Logger.getLogger(PortFactory.class.getName());
	
	/**
	 * Assigns a Receiver to a ConduitExitController in a Thread.
	 * 
	 * By evaluating the Future that is returned, it is possible to determine when this has taken place and what the actual assigned receiver was.
	 * The call is non-blocking, however, the returned Future can be evaluated with a blocking call.
	 */
	public <T extends Serializable> Receiver<T,?,?,?> getReceiver(ConduitExitControllerImpl<T> localInstance, PortalID otherSide) {
		try {
			return this.<T>getReceiverTask(localInstance, otherSide).call();
		} catch (Exception ex) {
			Logger.getLogger(PortFactory.class.getName()).log(Level.SEVERE, "Could not instantiate receiver", ex);
			return null;
		}
	}
	
	/**
	 * Assigns a Transmitter to a ConduitEntranceController in a Thread.
	 * 
	 * By evaluating the Future that is returned, it is possible to determine when this has taken place and what the actual assigned transmitter was.
	 * The call is non-blocking, however, the returned Future can be evaluated with a blocking call.
	 */
	public <T extends Serializable> Future<Transmitter<T,?,?,?>> getTransmitter(InstanceController ic, ConduitEntranceControllerImpl<T> localInstance, PortalID otherSide) {
		return executor.submit(this.<T>getTransmitterTask(ic, localInstance, otherSide));
	}

	/**
	 * Creates a task that will assign a receiver to a ConduitExitController.
	 * 
	 * In this task, the receiver must also be added to the messageProcessor, and the otherSide might have to be resolved.
	 */
	protected abstract <T extends Serializable> Callable<Receiver<T,?,?,?>> getReceiverTask(ConduitExitControllerImpl<T> localInstance, PortalID otherSide);
	
	/**
	 * Creates a task that will assign a transmitter to a ConduitEntranceController.
	 * 
	 * In this task, the otherSide might have to be resolved.
	 */
	protected abstract <T extends Serializable> Callable<Transmitter<T,?,?,?>> getTransmitterTask(InstanceController ic, ConduitEntranceControllerImpl<T> localInstance, PortalID otherSide);
	
	protected PortFactory(ResolverFactory rf, IncomingMessageProcessor msgProcessor) {
		this.executor = Executors.newCachedThreadPool();
		this.resolverFactory = rf;
		this.messageProcessor = msgProcessor;
	}
	
	/** Resolves a PortalID, if not already done. */
	protected boolean resolvePort(PortalID port) {
		if (!port.isResolved()) {
			try {
				resolverFactory.getResolver().resolveIdentifier(port);
				if (!port.isResolved()) return false;
			} catch (InterruptedException ex) {
				logger.log(Level.SEVERE, "Resolving port interrupted", ex);
				return false;
			}
		}
		return true;
	}
	
	public IncomingMessageProcessor getMessageProcessor() {
		return this.messageProcessor;
	}
	
	/** Frees all resources attached to the PortFactory. After this call, getReceiver() and getTransmitter() can not be called anymore. */
	public void dispose() {
		executor.shutdown();
	}
}