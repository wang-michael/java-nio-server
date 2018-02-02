package com.jenkov.nioserver.http;

import com.jenkov.nioserver.IMessageReader;
import com.jenkov.nioserver.Message;
import com.jenkov.nioserver.MessageBuffer;
import com.jenkov.nioserver.Socket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jjenkov on 18-10-2015.
 *
 * 对于每次读取的信息，有可能是一个完整的message，也有可能是0.5或者1.5个message
 * 在剩余信息到达之前如何处理已经到达的不完整信息是挑战
 *
 * HttpMessageReader需要负责检测到来的流中是否包含完整的信息，并且存储部分信息等待之后的信息到来组合成完整的信息
 *
 * 对于每一个连接，需要一个MessageReader对象来处理输入数据，每个MessageReader对象中需要一个resizable buffer来保存不完整的信息
 */
public class HttpMessageReader implements IMessageReader {

    //所有MessageReader实际上指向的是同一个messageBuffer，用来保存读到的不完整的数据
    public MessageBuffer messageBuffer    = null;
    //completeMessages中存储的是等待被处理的接收到的完整的信息
    private List<Message> completeMessages = new ArrayList<Message>();
    private Message       nextMessage      = null;

    public HttpMessageReader() {
    }

    @Override
    public void init(MessageBuffer readMessageBuffer) {
        this.messageBuffer        = readMessageBuffer;
        if (messageBuffer.smallMessageBufferFreeBlocks.readPos == 256) {
            System.out.println("debug!");
        }
        this.nextMessage          = messageBuffer.getMessage();
        this.nextMessage.metaData = new HttpHeaders();
    }

    //调用示例：socket.messageReader.read(socket, this.readByteBuffer);
    @Override
    public void read(Socket socket, ByteBuffer byteBuffer) throws IOException {
        int bytesRead = socket.read(byteBuffer);
        //将buffer由写模式切换到读模式
        byteBuffer.flip();

        //如果当前byteBuffer中没有剩余空间可用
        if(byteBuffer.remaining() == 0){
            byteBuffer.clear();
            return;
        }

        this.nextMessage.writeToMessage(byteBuffer);

        int endIndex = HttpUtil.parseHttpRequest(this.nextMessage.sharedArray, this.nextMessage.offset, this.nextMessage.offset + this.nextMessage.length, (HttpHeaders) this.nextMessage.metaData);
        if(endIndex != -1){
            //重新申请一块空间存储接收到的不完整的信息
            Message message = this.messageBuffer.getMessage();
            message.metaData = new HttpHeaders();

            message.writePartialMessageToMessage(nextMessage, endIndex);

            //completeMessages中保存的Message对应的空间是如何释放的？
            completeMessages.add(nextMessage);
            nextMessage = message;
        }
        byteBuffer.clear();
    }


    @Override
    public List<Message> getMessages() {
        return this.completeMessages;
    }

}
