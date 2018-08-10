package io.conio;

/**
 * <p>
 * The coroutine channel initializer, must set channel handler here.
 * </p>
 * @since 2018-08-09
 * @author little-pan
 */
public interface ChannelInitializer {

    void initialize(CoChannel channel, boolean serverSide);

}