package com.mmiladinovic.master;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;
import com.mmiladinovic.message.*;
import com.mmiladinovic.metrics.MetricsRegistry;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Works with a pool of workers, sending them work from the work queue
 */
public class WorkMaster extends AbstractActor {

    private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private final Map<ActorRef, Optional<AcceptedWork>> workers = new HashMap<>();
    private final Queue<AcceptedWork> workQ = new ArrayBlockingQueue<AcceptedWork>(10000);

    private final ActorRef workFeeder;

    // to receive our internal tick message to "prod" work feeder if SQS was empty on the last check
    private final Cancellable tick;
    private long lastAskedWorkFeeder;

    public WorkMaster(ActorRef workFeeder) {
        MetricsRegistry.registerGaugeMasterQueueDepth(workQ);

        receive(ReceiveBuilder
                .match(WorkerCreated.class, this::workerCreated)
                .match(WorkerRequestsWork.class, this::workerRequestsWork)
                .match(WorkIsDone.class, this::workIsDone)
                .match(Terminated.class, this::workerTerminated)
                .matchEquals("tick", this::tick)
                .matchAny(o -> {
                    log.info("accepting work {}", o);
                    if (workQ.offer(new AcceptedWork(sender(), o))) {
                        MetricsRegistry.meterWorkAccepted().mark();
                        notifyWorkers();
                    } else {
                        MetricsRegistry.meterWorkRejected().mark();
                        log.info("internal Q full. rejecting work {}", o);
                    }
                })
                .build());

        this.workFeeder = workFeeder;

        tick = getContext().system().scheduler().schedule(
                Duration.create(100, TimeUnit.MILLISECONDS),
                Duration.create(100, TimeUnit.MILLISECONDS),
                self(), "tick", getContext().dispatcher(), null);
    }

    @Override
    public void postStop() throws Exception {
        tick.cancel();
    }

    // --  message handlers
    private void workerCreated(WorkerCreated msg) {
        // create a deathwatch on the worker
        // add to workers pool
        // notify all workers
        log.info("new worker created: {}", msg.worker);
        getContext().watch(msg.worker);
        workers.put(msg.worker, Optional.empty());
        notifyWorkers();
    }

    private void workerRequestsWork(WorkerRequestsWork msg) {
        // if we have accepted work to be done
        // and the worker is one of ours
        // and the worker is not currently busy
        // then send him WorkToBeDone msg and update the worker status
        if (workers.containsKey(msg.worker)) {
            if (workQ.isEmpty()) {
                askForMoreWork();
                MetricsRegistry.meterAskForWorkInIdle().mark();
            }
            else if (!workers.get(msg.worker).isPresent()) {
                AcceptedWork workItem = workQ.poll();
                workers.put(msg.worker, Optional.of(workItem));
                msg.worker.tell(new WorkToBeDone(workItem.work), workItem.requestor);
            }
        }
    }

    private void workIsDone(WorkIsDone msg) {
        // if we know about this worker, then set its status to Optional.empty()
        if (workers.containsKey(msg.worker)) {
            workers.put(msg.worker, Optional.empty());
        }
        else {
            log.error("actor {} is reporting work is done but I don't know about him.", msg.worker);
        }
    }

    private void workerTerminated(Terminated terminated) {
        // check if its still one of our actors
        // and if so remove him from the pool but first re-assign the work through the inputQ
        ActorRef dead = terminated.actor();
        if (workers.containsKey(dead)) {
            if (workers.get(dead).isPresent()) {
                AcceptedWork work = workers.get(dead).get();
                self().tell(work.work, work.requestor);
            }
            workers.remove(dead);
        }
        else {
            log.error("Termination message came from actor we don't know about: {}", terminated.actor());
        }
    }

    private void notifyWorkers() {
        // send WorkIsReady to all available and non busy workers
        if (!workQ.isEmpty()) {
            workers.keySet().stream().
                    filter(worker -> !workers.get(worker).isPresent()).
                    forEach(worker -> worker.tell(new WorkIsReady(), self()));
        }
    }

    private void askForMoreWork() {
        lastAskedWorkFeeder = System.currentTimeMillis();
        workFeeder.tell(new FeedMoreWork(10, self()), self());
    }

    private void tick(String s) {
        long delta = System.currentTimeMillis() - lastAskedWorkFeeder;
        if (delta > 200) {
            log.info("delta expired. asking for more work");
            MetricsRegistry.meterProdForWork().mark();
            askForMoreWork();
        }
    }

    // actor creation
    public static Props props(ActorRef workFeeder) {
        return Props.create(WorkMaster.class, () -> new WorkMaster(workFeeder));
    }

    private static final class AcceptedWork implements Serializable {
        public final ActorRef requestor;
        public final Object work;

        public AcceptedWork(ActorRef requestor, Object work) {
            this.requestor = requestor;
            this.work = work;
        }
    }

}
