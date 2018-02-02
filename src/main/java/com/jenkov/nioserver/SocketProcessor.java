package com.jenkov.nioserver;

import com.jenkov.nioserver.http.HttpMessageReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;

/**
 * Created by jjenkov on 16-10-2015.
 *
 */
public class SocketProcessor implements Runnable {

    private Queue<Socket>  inboundSocketQueue   = null;

    //readMessageBuffer与writeMessageBuffer大小均为1MB
    private MessageBuffer  readMessageBuffer    = null; //todo   Not used now - but perhaps will be later - to check for space in the buffer before reading from sockets
    private MessageBuffer  writeMessageBuffer   = null; //todo   Not used now - but perhaps will be later - to check for space in the buffer before reading from sockets (space for more to write?)

    private IMessageReaderFactory messageReaderFactory = null;

    private Queue<Message> outboundMessageQueue = new LinkedList<>(); //todo use a better / faster queue.

    //key值为socketId，value为对应的客户端的socket，这个map的作用是将message与对应的socket连接起来，因为message中也存储了socketId
    private Map<Long, Socket> socketMap         = new HashMap<>();

    private ByteBuffer readByteBuffer  = ByteBuffer.allocate(1024 * 1024);
    private ByteBuffer writeByteBuffer = ByteBuffer.allocate(1024 * 1024);
    private Selector   readSelector    = null;
    private Selector   writeSelector   = null;

    private IMessageProcessor messageProcessor = null;
    private WriteProxy        writeProxy       = null;

    //为每个到来的客户端连接分配一个ID以作区分
    //nextSocketId如果超出long范围了怎么办？
    private long              nextSocketId = 16 * 1024; //start incoming socket ids from 16K - reserve bottom ids for pre-defined sockets (servers).

    private Set<Socket> emptyToNonEmptySockets = new HashSet<>();
    private Set<Socket> nonEmptyToEmptySockets = new HashSet<>();


    public SocketProcessor(Queue<Socket> inboundSocketQueue, MessageBuffer readMessageBuffer, MessageBuffer writeMessageBuffer, IMessageReaderFactory messageReaderFactory, IMessageProcessor messageProcessor) throws IOException {
        //inboundSocketQueue中存储了客户端到来的连接
        this.inboundSocketQueue = inboundSocketQueue;

        this.readMessageBuffer    = readMessageBuffer;
        this.writeMessageBuffer   = writeMessageBuffer;
        this.writeProxy           = new WriteProxy(writeMessageBuffer, this.outboundMessageQueue);

        this.messageReaderFactory = messageReaderFactory;

        this.messageProcessor     = messageProcessor;

        this.readSelector         = Selector.open();
        this.writeSelector        = Selector.open();
    }

    public void run() {
        while(true){
            try{
                executeCycle();
            } catch(IOException e){
                e.printStackTrace();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    public void executeCycle() throws IOException {
        takeNewSockets();
        readFromSockets();
        writeToSockets();
    }


    public void takeNewSockets() throws IOException {
        Socket newSocket = this.inboundSocketQueue.poll();

        while(newSocket != null){
            newSocket.socketId = this.nextSocketId++;
            newSocket.socketChannel.configureBlocking(false);

            newSocket.messageReader = this.messageReaderFactory.createMessageReader();
            //所有客户端连接共用一个readMessageBuffer
            newSocket.messageReader.init(this.readMessageBuffer);

            newSocket.messageWriter = new MessageWriter();

            this.socketMap.put(newSocket.socketId, newSocket);

            //将每个到来的新连接注册到readSelector中监测
            SelectionKey key = newSocket.socketChannel.register(this.readSelector, SelectionKey.OP_READ);
            key.attach(newSocket);

            newSocket = this.inboundSocketQueue.poll();
        }
    }


    public void readFromSockets() throws IOException {
        int readReady = this.readSelector.selectNow();

        if(readReady > 0){
            Set<SelectionKey> selectedKeys = this.readSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while(keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                readFromSocket(key);

                keyIterator.remove();
            }
            //在每次keyIterator.remove()条件下，有没有必要单独clear()？
            selectedKeys.clear();
        }
    }

    private void readFromSocket(SelectionKey key) throws IOException {
        Socket socket = (Socket) key.attachment();
        //先读数据进行数据分割
        socket.messageReader.read(socket, this.readByteBuffer);
        //获取读到的所有完整数据
        List<Message> fullMessages = socket.messageReader.getMessages();
        if(fullMessages.size() > 0) {
            for(Message message : fullMessages) {
                message.socketId = socket.socketId;
                //the message processor will eventually push outgoing messages into an IMessageWriter for this socket.
                this.messageProcessor.process(message, this.writeProxy);
                //释放处理完的数据占用的空间
                ((HttpMessageReader) socket.messageReader).messageBuffer.freeMessage(message);
            }

            fullMessages.clear();
        }
//        System.out.println("数据处理完：" + ((HttpMessageReader) socket.messageReader).messageBuffer.hashCode() +
//                        " readpos: " + ((HttpMessageReader) socket.messageReader).messageBuffer.smallMessageBufferFreeBlocks.readPos
//                        + " writepos: " + ((HttpMessageReader) socket.messageReader).messageBuffer.smallMessageBufferFreeBlocks.writePos
//                        + " flipeed: " + ((HttpMessageReader) socket.messageReader).messageBuffer.smallMessageBufferFreeBlocks.flipped
//        );
        if(socket.endOfStreamReached) {
            System.out.println("Socket closed: " + socket.socketId);
            System.out.println("----------------------------------");
            this.socketMap.remove(socket.socketId);
            key.attach(null);
            key.cancel();
            key.channel().close();
        }
    }


    public void writeToSockets() throws IOException {

        // Take all new messages from outboundMessageQueue
        takeNewOutboundMessages();

        // Cancel all sockets which have no more data to write.
        cancelEmptySockets();

        // Register all sockets that *have* data and which are not yet registered.
        registerNonEmptySockets();

        // Select from the Selector.
        int writeReady = this.writeSelector.selectNow();

        if(writeReady > 0){
            Set<SelectionKey>      selectionKeys = this.writeSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator   = selectionKeys.iterator();

            while(keyIterator.hasNext()){
                SelectionKey key = keyIterator.next();

                Socket socket = (Socket) key.attachment();

                socket.messageWriter.write(socket, this.writeByteBuffer);

                if(socket.messageWriter.isEmpty()){
                    this.nonEmptyToEmptySockets.add(socket);
                }

                keyIterator.remove();
            }

            selectionKeys.clear();

        }
    }

    private void registerNonEmptySockets() throws ClosedChannelException {
        for(Socket socket : emptyToNonEmptySockets){
            socket.socketChannel.register(this.writeSelector, SelectionKey.OP_WRITE, socket);
        }
        emptyToNonEmptySockets.clear();
    }

    private void cancelEmptySockets() {
        for(Socket socket : nonEmptyToEmptySockets){
            SelectionKey key = socket.socketChannel.keyFor(this.writeSelector);

            key.cancel();
        }
        nonEmptyToEmptySockets.clear();
    }

    private void takeNewOutboundMessages() {
        Message outMessage = this.outboundMessageQueue.poll();
        while(outMessage != null){
            Socket socket = this.socketMap.get(outMessage.socketId);

            if(socket != null){
                MessageWriter messageWriter = socket.messageWriter;
                if(messageWriter.isEmpty()) {
                    messageWriter.enqueue(outMessage);
                    nonEmptyToEmptySockets.remove(socket);
                    emptyToNonEmptySockets.add(socket);    //not necessary if removed from nonEmptyToEmptySockets in prev. statement.
                } else{
                   messageWriter.enqueue(outMessage);
                }
            }

            outMessage = this.outboundMessageQueue.poll();
        }
    }

}
