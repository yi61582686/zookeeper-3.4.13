package com.luban.server;

import org.apache.zookeeper.server.quorum.FastLeaderElection;

import java.util.concurrent.LinkedBlockingQueue;

public class QueueTest {

    public static void main(String[] args) {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

        queue.add("1");

//        queue.poll();
//        queue.peek();

        System.out.println(queue.size());
    }
}
