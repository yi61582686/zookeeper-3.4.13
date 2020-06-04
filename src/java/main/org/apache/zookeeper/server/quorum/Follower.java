/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.jute.Record;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.txn.TxnHeader;

/**
 * This class has the control logic for the Follower.
 */
public class Follower extends Learner{

    private long lastQueued;
    // This is the same object as this.zk, but we cache the downcast op
    final FollowerZooKeeperServer fzk;
    
    Follower(QuorumPeer self,FollowerZooKeeperServer zk) {
        this.self = self;
        this.zk=zk;
        this.fzk = zk;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Follower ").append(sock);
        sb.append(" lastQueuedZxid:").append(lastQueued);
        sb.append(" pendingRevalidationCount:")
            .append(pendingRevalidations.size());
        return sb.toString();
    }

    /**
     * the main method called by the follower to follow the leader
     *
     * @throws InterruptedException
     */
    void followLeader() throws InterruptedException {
        self.end_fle = Time.currentElapsedTime();
        long electionTimeTaken = self.end_fle - self.start_fle;
        self.setElectionTimeTaken(electionTimeTaken);
        LOG.info("FOLLOWING - LEADER ELECTION TOOK - {}", electionTimeTaken);
        self.start_fle = 0;
        self.end_fle = 0;
        fzk.registerJMX(new FollowerBean(this, zk), self.jmxLocalPeerBean);
        try {
            // 选举完成后，Follower需要和Leader单独建立一条socket连接，用来同步数据
            QuorumServer leaderServer = findLeader();            
            try {
                connectToLeader(leaderServer.addr, leaderServer.hostname); // 建立socket,连接leader
                long newEpochZxid = registerWithLeader(Leader.FOLLOWERINFO); // 注册，其实就是告诉Leader，当前Follower自己的信息，比如最大的zxid，并且Leader返回Leader当前最大的zxid。

                //check to see if the leader zxid is lower than ours
                //this should never happen but is just a safety check
                long newEpoch = ZxidUtils.getEpochFromZxid(newEpochZxid);
                // 安全检查，如果Leader的epoch小于Follower的epoch，这种情况是不应该出现的
                if (newEpoch < self.getAcceptedEpoch()) {
                    LOG.error("Proposed leader epoch " + ZxidUtils.zxidToString(newEpochZxid)
                            + " is less than our accepted epoch " + ZxidUtils.zxidToString(self.getAcceptedEpoch()));
                    throw new IOException("Error: Epoch of leader is lower");
                }
                syncWithLeader(newEpochZxid);    // 完成了数据同步，同步完成后会进行服务器初始化，从而可以处理客户端请求

                // 同时从Leader获取请求数据
                QuorumPacket qp = new QuorumPacket();
                while (this.isRunning()) {
                    // 如果超过socket的超时时间，没有读到数据(包括ping和其他类型的请求)，那么则抛异常，从而关闭socket，自杀
                    readPacket(qp);
                    processPacket(qp);
                }
            } catch (Exception e) {
                LOG.warn("Exception when following the leader", e);
                try {
                    sock.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
    
                // clear pending revalidations
                pendingRevalidations.clear();
            }
        } finally {
            zk.unregisterJMX((Learner)this);
        }
    }

    /**
     * Examine the packet received in qp and dispatch based on its contents.
     * @param qp
     * @throws IOException
     */
    protected void processPacket(QuorumPacket qp) throws IOException{
        switch (qp.getType()) {
        case Leader.PING:
//            System.out.println("处理ping");
            ping(qp);            
            break;
        case Leader.PROPOSAL:
            // 接受到提议，直接持久化，如果持久化成功了
            TxnHeader hdr = new TxnHeader();
            Record txn = SerializeUtils.deserializeTxn(qp.getData(), hdr);
            if (hdr.getZxid() != lastQueued + 1) {
                LOG.warn("Got zxid 0x"
                        + Long.toHexString(hdr.getZxid())
                        + " expected 0x"
                        + Long.toHexString(lastQueued + 1));
            }
            lastQueued = hdr.getZxid();
            fzk.logRequest(hdr, txn);
            break;
        case Leader.COMMIT:
            fzk.commit(qp.getZxid());
            break;
        case Leader.UPTODATE:
            LOG.error("Received an UPTODATE message after Follower started");
            break;
        case Leader.REVALIDATE:
            revalidate(qp);
            break;
        case Leader.SYNC:
            fzk.sync();
            break;
        default:
            LOG.error("Invalid packet type: {} received by Observer", qp.getType());
        }
    }

    /**
     * The zxid of the last operation seen
     * @return zxid
     */
    public long getZxid() {
        try {
            synchronized (fzk) {
                return fzk.getZxid();
            }
        } catch (NullPointerException e) {
            LOG.warn("error getting zxid", e);
        }
        return -1;
    }
    
    /**
     * The zxid of the last operation queued
     * @return zxid
     */
    protected long getLastQueued() {
        return lastQueued;
    }

    @Override
    public void shutdown() {    
        LOG.info("shutdown called", new Exception("shutdown Follower"));
        super.shutdown();
    }
}
