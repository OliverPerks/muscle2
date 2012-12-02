/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package muscle.core.conduit.filter;

import eu.mapperproject.jmml.util.FastArrayList;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.List;
import muscle.core.model.Observation;
import muscle.exception.MUSCLERuntimeException;

/**
 *
 * @author Joris Borgdorff
 */
public abstract class FilterChain<E extends Serializable, F extends Serializable> extends AbstractFilter<E,F> {
	private List<ThreadedFilter> threadedFilters;
	
	public void init(List<String> args) {
		this.threadedFilters = new FastArrayList<ThreadedFilter>();
		if (!args.isEmpty()) {
			QueueConsumer qc = automaticPipeline(args, this);
			this.setQueueConsumer(qc);
		}
	}
	
	public boolean isActive() {
		return this.consumer != null;
	}

	public void process(Observation obs) {
		if (this.isActive()) {
			put(obs);
			this.consumer.apply();
		} else {
			this.apply(obs);
		}
	}
	
	public void apply() {
		while (!incomingQueue.isEmpty()) {
			Observation message = incomingQueue.remove();
			this.apply(message);
		}
	}
	
	private QueueConsumer automaticPipeline(List<String> filterNames, QueueConsumer tailFilter) {
		QueueConsumer filter = tailFilter;
		for (int i = filterNames.size() - 1; i >= 0; i--) {
			filter = filterForName(filterNames.get(i), filter);
		}

		return filter;
	}

	public void dispose() {
		for (ThreadedFilter f : this.threadedFilters) {
			f.dispose();
		}
	}
	
	private QueueConsumer filterForName(String fullName, QueueConsumer tailFilter) {
		// split any args from the preceding filter name
		String[] tmp = fullName.split("_", 2); // 2 means only split once
		String name = tmp[0];
		String remainder = null;
		if (tmp.length > 1) {
			remainder = tmp[1];
		}

		Filter filter = null;

		// filters without args
		if (name.equals("null")) {
			filter = new NullFilter();
		} else if (name.equals("pipe")) {
			filter = new PipeFilter();
		} else if (name.equals("console")) {
			filter = new ConsoleWriterFilter();
		} else if (name.equals("linearinterpolation")) {
			filter = new LinearInterpolationFilterDouble();
		} else if (name.equals("thread")) {
			ThreadedFilter tfilter = new ThreadedFilter();
			tfilter.start();
			this.threadedFilters.add(tfilter);
		}// filters with mandatory args
		else if (name.equals("multiply")) {
			filter = new MultiplyFilterDouble(Double.valueOf(remainder));
		} else if (name.equals("drop")) {
			filter = new DropFilter(Integer.valueOf(remainder));
		} else if (name.equals("timeoffset")) {
			filter = new TimeOffsetFilter(Double.valueOf(remainder));
		} else if (name.equals("timefactor")) {
			filter = new TimeFactorFilter(Double.valueOf(remainder));
		} else if (name.equals("blockafter")) {
			filter = new BlockAfterTimeFilter(Double.valueOf(remainder));
		} else if (name.equals("lineartimeinterpolation")) {
			filter = new LinearTimeInterpolationFilterDouble(Integer.valueOf(remainder));
		} // assume name refers to a class name
		else {
			Class<? extends Filter> filterClass = null;
			double rem = 0d;
			if (remainder != null) {
				rem = Double.valueOf(remainder);
			}

			try {
				filterClass = (Class<? extends Filter>) Class.forName(name);
			} catch (ClassNotFoundException e) {
				throw new MUSCLERuntimeException(e);
			}


			// try to find constructor with tailFilter
			Constructor<? extends Filter> filterConstructor = null;
			try {
				if (remainder == null) {
					filterConstructor = filterClass.getDeclaredConstructor((Class[]) null);
				} else {
					filterConstructor = filterClass.getDeclaredConstructor(new Class[]{Double.TYPE});
				}
			} catch (java.lang.NoSuchMethodException e) {
				throw new MUSCLERuntimeException(e);
			}

			try {
				if (remainder == null) {
					filter = filterConstructor.newInstance();
				} else {
					filter = filterConstructor.newInstance(rem);
				}
				if (filter instanceof ThreadedFilter) {
					ThreadedFilter tfilter = (ThreadedFilter)filter;
					tfilter.start();
					threadedFilters.add(tfilter);
				}
			} catch (java.lang.InstantiationException e) {
				throw new MUSCLERuntimeException(e);
			} catch (java.lang.IllegalAccessException e) {
				throw new MUSCLERuntimeException(e);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw new MUSCLERuntimeException(e);
			}

		}

		if (filter != null) {
			filter.setQueueConsumer(tailFilter);
			return filter;
		} else {
			return tailFilter;
		}
	}
}