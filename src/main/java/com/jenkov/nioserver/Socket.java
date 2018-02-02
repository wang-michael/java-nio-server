package com.jenkov.nioserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Created by jjenkov on 16-10-2015.
 * SocketChannel的封装，以便更方便的处理非阻塞读写，sockId可用来记录不完整的Message与SocketChannel之间的对应关系
 */
public class Socket {

    public long socketId;

    public SocketChannel  socketChannel = null;
    public IMessageReader messageReader = null;
    public MessageWriter  messageWriter = null;

    public boolean endOfStreamReached = false;

    public Socket() {
    }

    public Socket(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
    }

    //从Channel中读数据到ByteBuffer中
    public int read(ByteBuffer byteBuffer) throws IOException {
        int bytesRead = this.socketChannel.read(byteBuffer);
        int totalBytesRead = bytesRead;

        //由于SocketChannel配置为非阻塞模式，需要一直读直到读完可读的所有数据
        while(bytesRead > 0){
            bytesRead = this.socketChannel.read(byteBuffer);
            totalBytesRead += bytesRead;
        }
        if(bytesRead == -1){
            this.endOfStreamReached = true;
        }

        return totalBytesRead;
    }

    //将byteBuffer中数据写到Channel中
    public int write(ByteBuffer byteBuffer) throws IOException{
        int bytesWritten      = this.socketChannel.write(byteBuffer);
        int totalBytesWritten = bytesWritten;

        while(bytesWritten > 0 && byteBuffer.hasRemaining()){
            bytesWritten = this.socketChannel.write(byteBuffer);
            totalBytesWritten += bytesWritten;
        }

        return totalBytesWritten;
    }


}
