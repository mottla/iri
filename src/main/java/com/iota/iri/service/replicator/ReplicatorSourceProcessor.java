package com.iota.iri.service.replicator;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iota.iri.Neighbor;
import com.iota.iri.hash.Curl;
import com.iota.iri.model.Hash;
import com.iota.iri.service.Node;
import com.iota.iri.service.viewModels.TransactionViewModel;

public class ReplicatorSourceProcessor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ReplicatorSourceProcessor.class);

    private Socket connection;

    final static int TRANSACTION_PACKET_SIZE = Node.TRANSACTION_PACKET_SIZE;
    private volatile boolean shutdown = false;
    
    private boolean existingNeighbor;
    
    private Neighbor neighbor;
    


    public ReplicatorSourceProcessor(Socket connection) {
        this.connection = connection;
    }
    
    private final DatagramPacket sendingPacket = new DatagramPacket(new byte[TRANSACTION_PACKET_SIZE], TRANSACTION_PACKET_SIZE);

    @Override
    public void run() {
        int count;
        byte[] data = new byte[2000];
        int offset = 0;
        boolean isNew;
        boolean finallyClose = true;

        try {
            final Curl curl = new Curl();
            final int[] receivedTransactionTrits = new int[TransactionViewModel.TRINARY_SIZE];
            final byte[] requestedTransaction = new byte[Hash.SIZE_IN_BYTES];

            SocketAddress address = connection.getRemoteSocketAddress();
            InetSocketAddress inet_socket_address = (InetSocketAddress) address;
            InetSocketAddress inet_socket_address_normalized = new InetSocketAddress(inet_socket_address.getAddress(),Replicator.REPLICATOR_PORT);

            existingNeighbor = false;
            List<Neighbor> neighbors = Node.instance().getNeighbors();            
            neighbors.forEach(n -> {
                if (n.isTcpip() && n.getAddress().equals(inet_socket_address_normalized)) {
                    existingNeighbor = true;
                    neighbor = n;
                }
            });
            
            //Neighbor fresh_neighbor = new Neighbor(inet_socket_address_normalized, true, false);
            if (!existingNeighbor) {
                StringBuffer sb = new StringBuffer(80);
                sb.append("***** NETWORK ALERT ***** Got connected from unknown neighbor tcp://")
                    .append(inet_socket_address.getHostName())
                    .append(":")
                    .append(String.valueOf(inet_socket_address.getPort()))
                    .append(" (")
                    .append(inet_socket_address.getAddress().getHostAddress())
                    .append(") - closing connection");
                log.info(sb.toString());
                connection.close();
                return;
                /* -- This is possible code if tethering is disabled 
                Node.instance().getNeighbors().add(fresh_neighbor);
                neighbor = fresh_neighbor;
                */
            }
            
            if ( neighbor.getSource() != null ) {
                log.info("Source {} already connected", inet_socket_address.getAddress().getHostAddress());
                finallyClose = false;
                return;
            }
            neighbor.setTcpip(true);
            neighbor.setSource(connection);
            
            if (neighbor.getSink() == null) {
                log.info("Creating sink for {}", neighbor.getHostAddress());
                ReplicatorSinkPool.instance().createSink(neighbor);
            }

            InputStream stream = connection.getInputStream();
            
            if (connection.isConnected()) {
                log.info("----- NETWORK INFO ----- Source {} is connected", inet_socket_address.getAddress().getHostAddress());
            }
            
            connection.setSoTimeout(0);  // infinite timeout - blocking read


            while (!shutdown) {
                boolean readError = false;

                while (((count = stream.read(data, offset, TRANSACTION_PACKET_SIZE - offset)) != -1) && (offset < TRANSACTION_PACKET_SIZE)) {
                    offset += count;
                }
              
                if ( count == -1 || connection.isClosed() ) {
                    readError = true;
                    break;
                }
                
                offset = 0;

                if (!readError) {
                    try {
                        Node.instance().processReceivedData(data, address, curl, receivedTransactionTrits, requestedTransaction);
                    }
                      catch (IllegalStateException e) {
                        log.error("Queue is full for neighbor IP {}",inet_socket_address.getAddress().getHostAddress());
                    } catch (final RuntimeException e) {
                        log.error("Transdaction processing runtime exception ",e);
                        neighbor.incInvalidTransactions();
                    } catch (InterruptedException e) {
                        log.error("Interrupted");
                    } catch (ExecutionException e) {
                        log.error("Transdaction propagation exception ",e);
                    } catch (Exception e) {
                        log.info("Transdaction processing exception " + e.getMessage());
                        log.error("Transdaction processing exception ",e);
                    }
                }
                else {
                    log.error("***** NETWORK ALERT ***** TCP connection reset by network {}, source closed", neighbor.getHostAddress());
                    break;
                }
            }
        } catch (IOException e) {
            log.error("***** NETWORK ALERT ***** TCP connection reset by neighbor {}, source closed, {}", neighbor.getHostAddress(), e.getMessage());
            ReplicatorSinkPool.instance().shutdownSink(neighbor);
        } finally {
            if (neighbor != null) {
                if (finallyClose) {
                    ReplicatorSinkPool.instance().shutdownSink(neighbor);
                    neighbor.setSource(null);
                    neighbor.setSink(null);
                }                   
            }
        }
    }
}
