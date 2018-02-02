# java-nio-server  
使用JDK NIO原生API实现的简单HTTP服务器。   

线程模型比较简单，只有两个线程，accepterThread接收客户端请求后放入BlockingQueue，processorThread从BlockingQueue中取出请求并处理。  

processorThread处理时使用readSelector监测请求的读事件，使用writeSelector监测请求的写事件，在readSelector中读到完整数据之后会先进行解析，之后加入writeSelector的待处理数据队列中等待处理。使用readSelector和writeSelector分别监测请求的读写事件的好处在于逻辑清晰，耦合度小，只有读到完整数据之后才将请求交给writeSelector进行监测，减少了无用监测的消耗。  

源码forked by jjenkov，在其基础上添加了对于处理完的连接数据占用存储空间的释放功能，使得可以支持更多的连接。  

源码中对于所有到来的连接，使用了共享的readMessageBuffer接收数据，使用共享的writeMessageBuffer写出数据。以readMessageBuffer为例，其中共包含了三种类型的存储空间：1024个4KB大小、128个128KB大小、16个1MB大小，如下图：  
<img src="/img/selectBufferSize.jpg" width="350" height="350" alt="readMessageBuffer三种存储类型" />
<center>图1：readMessageBuffer三种存储类型</center>  

使用非阻塞IO的难点之一需要保存请求当前接收到的不完整信息直到所有信息到来。使用不同类型的存储空间的好处在于当要处理的不完整的请求数据4KB保存不了时可以扩容为128KB或者1MB。  

对于MessageBuffer中三种不同类型的存储空间，使用QueueIntFlip来记录其空间分配情况，具体记录方式如下图：  
<img src="/img/QueueIntFlip.jpg" width="400" height="400" alt="MessageBuffer空间分配情况记录" />
<center>图2：MessageBuffer空间分配情况记录</center>    

更多关于项目的介绍，参见：[Java NIO: Non-blocking Server](http://tutorials.jenkov.com/java-nio/non-blocking-server.html)   