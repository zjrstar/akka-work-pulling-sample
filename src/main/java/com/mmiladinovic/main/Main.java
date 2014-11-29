package com.mmiladinovic.main;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import com.mmiladinovic.master.WorkMaster;
import com.mmiladinovic.worker.HelloWorldWorker;
import scala.concurrent.duration.Duration;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Created by miroslavmiladinovic on 29/11/2014.
 */
public class Main {

    public static void main(String args[]) {

        ActorSystem system = ActorSystem.create("akka-work-pulling-hello");

        ActorRef master = system.actorOf(WorkMaster.props(), "master");
        for (int i = 0; i < 5; i++) {
            ActorRef worker = system.actorOf(HelloWorldWorker.props(master.path().toString()), "worker-"+i);
        }

        system.scheduler().schedule(
                Duration.Zero(), Duration.create(10, TimeUnit.MILLISECONDS),
                (Runnable) () -> master.tell(UUID.randomUUID().toString(), ActorRef.noSender()),
                system.dispatcher());
    }
}
