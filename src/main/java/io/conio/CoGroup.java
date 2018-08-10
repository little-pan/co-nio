package io.conio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.*;

import com.offbynull.coroutines.user.Continuation;
import com.offbynull.coroutines.user.Coroutine;
import com.offbynull.coroutines.user.CoroutineRunner;
import io.conio.util.IoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  <p>
 *  The coroutine group that creates and schedules coroutines such as accept-co, channel-co etc.
 *  </p>
 * @since 2018-08-09
 * @author little-pan
 */
public class CoGroup {
    final static Logger log = LoggerFactory.getLogger(CoGroup.class);

    private String name = "coGroup";
    private boolean useAio;
    private boolean daemon;
    private String host;
    private int port = 9696;
    private int backlog = 1000;

    private volatile boolean stopped;
    private volatile boolean shutdown;
    private IoGroup ioGroup;

    private ChannelInitializer initializer;

    protected CoGroup(){

    }

    public void start(){
        final String name = getName();
        final IoGroup group;
        if(isUseAio()){
            group = bootAio(name + "-aio");
        }else{
            group = bootNio(name + "-nio");
        }
        group.start();
        ioGroup = group;
    }

    public final boolean isStopped(){
        return stopped;
    }

    public void shutdown(){
        shutdown = true;
    }

    public final boolean isShutdown(){
        return shutdown;
    }

    public void await(){
        final IoGroup group = ioGroup;
        if(group == null){
            return;
        }
        group.await();
    }

    public ChannelInitializer channelInitializer(){
        return initializer;
    }

    public void connect(String host, int port){
        connect(new InetSocketAddress(host, port));
    }

    public void connect(String host, int port, CoHandler handler){
        connect(new InetSocketAddress(host, port), handler);
    }

    public void connect(InetSocketAddress remote){
        connect(remote, null);
    }

    public void connect(InetSocketAddress remote, CoHandler handler){
        if(isStopped()){
            throw new IllegalStateException(name+" has stopped");
        }
        if(isShutdown()){
            throw new IllegalStateException(name+" has shutdown");
        }
        ioGroup.connect(new ConnectRequest(remote, handler));
    }

    public void connect(Continuation co, CoChannel coOwner, String host, int port){
        connect(co, coOwner, new InetSocketAddress(host, port));
    }

    public void connect(Continuation co, CoChannel coOwner, String host, int port, CoHandler handler){
        connect(co, coOwner, new InetSocketAddress(host, port), handler);
    }

    public void connect(Continuation co, CoChannel coOwner, InetSocketAddress remote){
        connect(co, coOwner, remote, null);
    }

    public void connect(Continuation co, CoChannel coOwner, InetSocketAddress remote, CoHandler handler){
        if(isStopped()){
            throw new IllegalStateException(name+" has stopped");
        }
        if(isShutdown()){
            throw new IllegalStateException(name+" has shutdown");
        }
        ioGroup.connect(co, coOwner, new ConnectRequest(remote, handler));
    }

    protected IoGroup bootNio(String name){
        boolean failed;

        final Selector selector;
        Selector sel = null;
        failed = true;
        try{
            sel = Selector.open();
            selector = sel;
            failed = false;
        }catch (IOException e){
            throw new RuntimeException(e);
        }finally {
            if(failed){
                IoUtils.close(sel);
            }
        }

        final ServerSocketChannel serverChan;
        ServerSocketChannel chan = null;
        failed = true;
        try{
            final String host = getHost();
            if(host != null){
                chan = ServerSocketChannel.open();
                chan.configureBlocking(false);
                chan.bind(new InetSocketAddress(host, getPort()), getBacklog());
                chan.register(selector, SelectionKey.OP_ACCEPT);
            }
            serverChan = chan;
            failed = false;
        }catch (IOException e){
            throw new RuntimeException(e);
        }finally{
            if(failed){
                IoUtils.close(chan);
            }
        }

        return new NioGroup(this, name, serverChan, selector);
    }

    protected IoGroup bootAio(String name){
        boolean failed;

        final ExecutorService ioExec = Executors.newFixedThreadPool(1, (r) -> {
            final Thread t = new Thread(r, "aio-exec");
            t.setDaemon(isDaemon());
            return t;
        });
        final AsynchronousChannelGroup chanGroup;
        failed = true;
        try{
            chanGroup = AsynchronousChannelGroup.withThreadPool(ioExec);
            failed = false;
        }catch(IOException e){
            throw new RuntimeException(e);
        }finally {
            if(failed){
                ioExec.shutdown();
            }
        }

        final AsynchronousServerSocketChannel serverChan;
        AsynchronousServerSocketChannel chan = null;
        failed = true;
        try{
            final String host = getHost();
            if(host != null) {
                chan = AsynchronousServerSocketChannel.open(chanGroup);
                chan.bind(new InetSocketAddress(host, getPort()), getBacklog());
            }
            serverChan = chan;
            failed = false;
        }catch(IOException e){
            throw new RuntimeException(e);
        }finally {
            if(failed){
                IoUtils.close(chan);
                chanGroup.shutdown();
                ioExec.shutdown();
            }
        }

        return new AioGroup(this, name, serverChan, chanGroup);
    }

    static abstract class IoGroup implements Runnable {
        final String name;
        final CoGroup coGroup;

        protected Thread runner;
        private int nextId;

        protected IoGroup(CoGroup coGroup, String name){
            this.coGroup = coGroup;
            this.name = name;
        }

        @Override
        public abstract void run();

        public abstract void connect(Continuation co, CoChannel coOwner, ConnectRequest request);

        public abstract void connect(ConnectRequest request);

        public void start(){
            final Thread t = new Thread(this, name);
            t.setDaemon(coGroup.isDaemon());
            t.start();
            runner = t;
        }

        public void await(){
            try {
                runner.join();
            }catch(InterruptedException e){}
        }

        public final boolean inGroup(){
            return (Thread.currentThread() == runner);
        }

        public int nextId(){
            return nextId++;
        }

    }// IoGroup

    interface ResultHandler {
        void handle();
    }

    static class NioGroup extends  IoGroup {
        final BlockingQueue<ConnectRequest> creqQueue;

        final ServerSocketChannel serverChan;
        final Selector selector;

        public NioGroup(CoGroup coGroup, String name, ServerSocketChannel serverChan, Selector selector){
            super(coGroup, name);
            this.serverChan = serverChan;
            this.selector = selector;
            this.creqQueue = new LinkedTransferQueue<>();
        }

        @Override
        public void run(){
            try{
                log.info("Started on {}:{}", coGroup.host, coGroup.port);

                for(;!coGroup.isStopped();){
                    final int n = selector.select();
                    if(n > 0){
                        final Set<SelectionKey> keys = selector.selectedKeys();
                        final Iterator<SelectionKey> i = keys.iterator();
                        for(; i.hasNext();i.remove()){
                            final SelectionKey key = i.next();
                            if(!key.isValid()){
                                continue;
                            }
                            if(key.isAcceptable()){
                                continue;
                            }
                            if(key.isConnectable()){
                                continue;
                            }
                            if(key.isReadable()){

                            }
                            if(key.isValid() && key.isWritable()){

                            }
                        }
                    }
                }

                IoUtils.close(serverChan);
                IoUtils.close(selector);
            }catch(final IOException e){
                log.error("Nio group fatal error", e);
            }finally {
                coGroup.stopped = true;
                coGroup.ioGroup = null;
            }
        }

        @Override
        public void connect(Continuation co, CoChannel coOwner, ConnectRequest request){
            creqQueue.offer(request);
            selector.wakeup();
        }

        @Override
        public void connect(ConnectRequest request){
            creqQueue.offer(request);
            selector.wakeup();
        }

    }// NioGroup

    static class AioGroup extends IoGroup {
        final BlockingQueue<ResultHandler> cQueue = new LinkedTransferQueue<>();

        final AsynchronousServerSocketChannel serverChan;
        final AsynchronousChannelGroup chanGroup;

        public AioGroup(CoGroup coGroup, String name, AsynchronousServerSocketChannel serverChan,
                        AsynchronousChannelGroup chanGroup){
            super(coGroup, name);
            this.serverChan = serverChan;
            this.chanGroup  = chanGroup;
        }

        @Override
        public void run() {
            try{
                CoAcceptor coAcceptor = null;
                if(serverChan != null){
                    coAcceptor = new CoAcceptor(this, serverChan);
                    coAcceptor.start();
                }
                log.info("Started on {}:{}", coGroup.host, coGroup.port);

                for (;!coGroup.isStopped();){
                    final ResultHandler handler = cQueue.poll(1L, TimeUnit.SECONDS);
                    if(handler != null){
                        handler.handle();
                    }
                    if(coGroup.isShutdown()){
                        for(;;){
                            final ResultHandler h = cQueue.poll();
                            if(h == null){
                                break;
                            }
                            h.handle();
                        }
                        break;
                    }
                }

                if(coAcceptor != null){
                    coAcceptor.stop();
                }
                IoUtils.close(serverChan);
                cQueue.clear();
            }catch(InterruptedException e){
                log.warn("Exit: interrupted");
            }finally{
                coGroup.stopped = true;
                coGroup.ioGroup = null;
            }
        }

        @Override
        public void connect(final Continuation co, final CoChannel coOwner, final ConnectRequest request){
            final ConnectHandler handler = new ConnectHandler(this, request);
            handler.connect(co, coOwner);
        }

        @Override
        public void connect(final ConnectRequest request){
            final ConnectHandler handler = new ConnectHandler(this, request);
            handler.connect();
        }

        public boolean offer(ResultHandler result){
            return cQueue.offer(result);
        }

        static class ConnectHandler implements ResultHandler, CompletionHandler<Void, ConnectRequest> {
            CoChannel coOwner;

            final AioGroup aioGroup;
            final ConnectRequest request;

            private AioCoChannel coChan;
            AsynchronousSocketChannel channel;
            Throwable cause;

            public ConnectHandler(AioGroup aioGroup, ConnectRequest request){
                this.aioGroup= aioGroup;
                this.request = request;
            }

            private void openConnection(){
                AsynchronousSocketChannel chan = null;
                boolean failed = true;
                try {
                    chan = AsynchronousSocketChannel.open(aioGroup.chanGroup);
                    channel = chan;
                    failed = false;
                }catch(final IOException e){
                    throw new RuntimeException(e);
                }finally {
                    if(failed){
                        IoUtils.close(chan);
                    }
                }
            }

            public void connect(){
                openConnection();
                log.debug("{}: connect to {}", aioGroup.name, request.remote);
                channel.connect(request.remote, request, this);
            }

            public void connect(final Continuation co, final CoChannel coOwner){
                connect();
                log.debug("{}: wait for conn completion", aioGroup.name);
                this.coOwner = coOwner;
                co.suspend();
            }

            @Override
            public void handle() {
                boolean failed = true;
                log.debug("{}: Handle connection result", aioGroup.name);
                try{
                    final CoGroup coGroup = aioGroup.coGroup;
                    coChan = new AioCoChannel(aioGroup, channel);
                    coGroup.channelInitializer().initialize(coChan, false);
                    CoHandler handler = request.handler;
                    if(handler == null && (handler=coChan.handler()) == null){
                        log.warn("{}: Connect handler not set, so close the channel", aioGroup.name);
                        return;
                    }
                    coChan.handler(handler);
                    if(cause != null){
                        handler.uncaught(cause);
                        return;
                    }
                    log.debug("{}: start a new CoChannel {}", aioGroup.name, coChan.name);
                    coChan.resume();
                    failed = false;
                }finally {
                    if(failed){
                        IoUtils.close(coChan);
                        IoUtils.close(channel);
                    }
                    if(coOwner != null){
                        coOwner.resume();
                    }
                }
            }

            @Override
            public void completed(Void none, ConnectRequest request) {
                final ConnectHandler handler = new ConnectHandler(aioGroup, request);
                handler.channel = channel;
                handler.coOwner = coOwner;
                aioGroup.offer(handler);
            }

            @Override
            public void failed(Throwable cause, ConnectRequest request) {
                final ConnectHandler handler = new ConnectHandler(aioGroup, request);
                handler.cause  = cause;
                handler.coOwner= coOwner;
                aioGroup.offer(handler);
            }
        }

        // Accept coroutine.
        static class CoAcceptor implements Coroutine {
            final static Logger log = LoggerFactory.getLogger(CoAcceptor.class);

            final String name = "aioAccept-co";

            final CoroutineRunner runner = new CoroutineRunner(this);
            final AcceptHandler handler = new AcceptHandler();

            final AsynchronousServerSocketChannel chan;
            final AioGroup aioGroup;
            final CoGroup coGroup;

            boolean stopped;

            public CoAcceptor(final AioGroup aioGroup, AsynchronousServerSocketChannel chan){
                this.aioGroup = aioGroup;
                this.coGroup  = aioGroup.coGroup;
                this.chan = chan;
            }

            @Override
            public void run(Continuation co){
                log.info("{}: started", name);
                for (;!coGroup.isShutdown() && !stopped;){
                    chan.accept(this, handler);
                    co.suspend();
                }
                log.info("{}: stopped", name);
            }

            public boolean resume(){
                return runner.execute();
            }

            public boolean start(){
                return resume();
            }

            public boolean stop(){
                stopped = true;
                return resume();
            }

            class AcceptResultHandler implements ResultHandler {
                final AsynchronousSocketChannel chan;
                final Throwable cause;

                public AcceptResultHandler(AsynchronousSocketChannel chan){
                    this(chan, null);
                }

                public AcceptResultHandler(Throwable cause){
                    this(null, cause);
                }

                private AcceptResultHandler(AsynchronousSocketChannel chan, Throwable cause){
                    this.chan = chan;
                    this.cause= cause;
                }

                @Override
                public void handle(){
                    final CoAcceptor acceptor = CoAcceptor.this;
                    if(cause != null){
                        acceptor.stop();
                        log.warn(name+" error", cause);
                        return;
                    }
                    // Start a coroutine for handle socket channel
                    boolean failed = true;
                    try{
                        final AioGroup aioGroup = CoAcceptor.this.aioGroup;
                        final AioCoChannel coChan = new AioCoChannel(aioGroup, chan);
                        log.debug("{}: accept a new coChannel {}", name, coChan.name);
                        coGroup.channelInitializer().initialize(coChan, true);
                        if(coChan.handler() == null){
                            log.warn("{}: Channel handler not set, so close the channel");
                            IoUtils.close(chan);
                            return;
                        }
                        coChan.resume();
                        failed = false;
                    }finally{
                        acceptor.resume();
                        if(failed){
                            IoUtils.close(chan);
                        }
                    }
                }
            }

            class AcceptHandler implements CompletionHandler<AsynchronousSocketChannel, CoAcceptor> {

                @Override
                public void completed(AsynchronousSocketChannel result, CoAcceptor acceptor){
                    aioGroup.offer(new AcceptResultHandler(result));
                }

                @Override
                public void failed(Throwable cause, CoAcceptor acceptor){
                    aioGroup.offer(new AcceptResultHandler(cause));
                }

            }

        }// CoAcceptor

        static class AioCoChannel extends CoChannel {
            final AioGroup aioGroup;
            final AsynchronousSocketChannel chan;
            final IoHandler handler = new IoHandler();
            private IoResultHandler result;

            public AioCoChannel(final AioGroup aioGroup, AsynchronousSocketChannel chan){
                super(aioGroup.nextId(), aioGroup.coGroup);
                this.aioGroup = aioGroup;
                this.chan = chan;
            }

            @Override
            public int read(Continuation co, ByteBuffer dst) throws IOException {
                if(!dst.hasRemaining()){
                    return 0;
                }
                try{
                    chan.read(dst, null, handler);
                    co.suspend();
                    if(result.cause != null){
                        throw new IOException(result.cause);
                    }
                    return result.bytes;
                } finally {
                    result = null;
                }
            }

            @Override
            public int write(Continuation co, ByteBuffer src) throws IOException {
                if(!src.hasRemaining()){
                    return 0;
                }
                try{
                    chan.write(src, null, handler);
                    co.suspend();
                    if(result.cause != null){
                        throw new IOException(result.cause);
                    }
                    return result.bytes;
                } finally {
                    result = null;
                }
            }

            @Override
            public boolean isOpen() {
                return chan.isOpen();
            }

            @Override
            public void close() {
                IoUtils.close(chan);
            }

            // Running in io threads.
            class IoHandler implements CompletionHandler<Integer, Void> {

                @Override
                public void completed(Integer result, Void attachment) {
                    aioGroup.offer(new IoResultHandler(result));
                }

                @Override
                public void failed(Throwable cause, Void attachment) {
                    aioGroup.offer(new IoResultHandler(cause));
                }
            }// IoHandler

            class IoResultHandler implements ResultHandler {

                final Integer bytes;
                final Throwable cause;

                IoResultHandler(Integer bytes){
                    this(bytes, null);
                }

                IoResultHandler(Throwable cause){
                    this(null, cause);
                }

                IoResultHandler(Integer bytes, Throwable cause){
                    this.bytes = bytes;
                    this.cause = cause;
                }

                @Override
                public void handle() {
                    result = this;
                    resume();
                }
            }// IoResultHandler

        }// AioCoChannel

    }// AioGroup

    public String getName(){
        return name;
    }

    public boolean isUseAio(){
        return useAio;
    }

    public boolean isDaemon(){
        return daemon;
    }

    public String getHost(){
        return host;
    }

    public int getPort(){
        return port;
    }

    public int getBacklog(){
        return backlog;
    }

    public final static Builder newBuilder(){
        return new Builder();
    }

    public static class Builder {
        private CoGroup group;

        protected Builder(){
            this.group = new CoGroup();
        }

        public Builder setName(String name){
            group.name = name;
            return this;
        }

        public Builder useAio(boolean use){
            group.useAio = use;
            return this;
        }

        public Builder setDaemon(boolean daemon){
            group.daemon = daemon;
            return this;
        }

        public Builder setHost(String host){
            group.host = host;
            return this;
        }

        public Builder setPort(int port){
            group.port = port;
            return this;
        }

        public Builder setBacklog(int backlog){
            group.backlog = backlog;
            return this;
        }

        public Builder channelInitializer(ChannelInitializer initializer){
            group.initializer = initializer;
            return this;
        }

        public CoGroup build(){
            if(group.channelInitializer() == null){
                throw new IllegalStateException("Channel initializer not set");
            }
            return group;
        }

    }// Builder

    static class ConnectRequest {
        final InetSocketAddress remote;
        final CoHandler handler;

        public ConnectRequest(InetSocketAddress remote, CoHandler handler){
            this.remote = remote;
            this.handler= handler;
        }
    }

}