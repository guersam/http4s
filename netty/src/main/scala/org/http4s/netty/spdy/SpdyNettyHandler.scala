package org.http4s.netty.spdy

import scala.util.control.Exception.allCatch

import java.net.{URI, InetSocketAddress}
import java.util.concurrent.ConcurrentHashMap

import io.netty.handler.codec.spdy._
import io.netty.channel.{ChannelFuture, ChannelFutureListener, ChannelHandlerContext}

import scalaz.concurrent.Task

import org.http4s.util.middleware.PushSupport
import org.http4s._
import org.http4s.netty.NettySupport
import org.http4s.Response
import org.http4s.netty.utils.SpdyStreamManager
import io.netty.buffer.ByteBuf


/**
* @author Bryce Anderson
*         Created on 11/28/13
*/
class SpdyNettyHandler(srvc: HttpService,
                  val spdyversion: Int,
                  val localAddress: InetSocketAddress,
                  val remoteAddress: InetSocketAddress)
          extends NettySupport[SpdyFrame, SpdySynStreamFrame]
          with SpdyStreamManager
          with SpdyConnectionWindow {

  import NettySupport._

  /** Serves as a repository for active streams
    * If a stream is canceled, it get removed from the map. The allows the client to reject
    * data that it knows is already cached and this backend abort the outgoing stream
    */
  private val activeStreams = new ConcurrentHashMap[Int, SpdyStream]
  private var _ctx: ChannelHandlerContext = null

  def ctx = _ctx

  def registerStream(stream: SpdyStream): Boolean = {
    activeStreams.put(stream.streamid, stream) == null
  }

  val serverSoftware = ServerSoftware("HTTP4S / Netty / SPDY")

  val service = PushSupport(srvc)

  protected def writeBodyBytes(streamid: Int, buff: ByteBuf): ChannelFuture = {
    ctx.writeAndFlush(new DefaultSpdyDataFrame(streamid, buff))
  }

  protected def writeEndBytes(streamid: Int, buff: ByteBuf, t: Option[TrailerChunk]): ChannelFuture = {
    t.fold{
      val msg = new DefaultSpdyDataFrame(streamid, buff)
      msg.setLast(true)
      ctx.writeAndFlush(msg)
    }{ t =>
      if (buff.readableBytes() > 0) ctx.write(new DefaultSpdyDataFrame(streamid, buff))
      val msg = new DefaultSpdyHeadersFrame(streamid)
      t.headers.foreach( h => msg.headers().add(h.name.toString, h.value) )
      ctx.writeAndFlush(msg)
    }
  }

  override def channelRegistered(ctx: ChannelHandlerContext) {
    _ctx = ctx
  }

  def streamFinished(id: Int) {
    logger.trace(s"Stream $id finished. Closing.")
    if (activeStreams.remove(id) == null) logger.warn(s"Stream id $id for address $remoteAddress was empty.")
  }

  def closeSpdyWindow(): Unit = {
    foreachStream { s =>
      s.closeSpdyWindow()
      s.close()
    }
    ctx.close()
  }

  override protected def toRequest(ctx: ChannelHandlerContext, req: SpdySynStreamFrame): Request = {
    val uri = new URI(SpdyHeaders.getUrl(spdyversion, req))
    val scheme = Option(SpdyHeaders.getScheme(spdyversion, req)).getOrElse{
      logger.warn(s"${remoteAddress}: Request doesn't have scheme header")
      "https"
    }

    val servAddr = ctx.channel.remoteAddress.asInstanceOf[InetSocketAddress]
    val replyStream = new SpdyReplyStream(req.getStreamId, ctx, this, initialWindow)
    assert(activeStreams.put(req.getStreamId, replyStream) == null)
    Request(
      requestMethod = Method(SpdyHeaders.getMethod(spdyversion, req).name),
      //scriptName = contextPath,
      pathInfo = uri.getRawPath,
      queryString = uri.getRawQuery,
      protocol = getProtocol(req),
      headers = toHeaders(req.headers),
      urlScheme = HttpUrlScheme(scheme),
      serverName = servAddr.getHostName,
      serverPort = servAddr.getPort,
      serverSoftware = serverSoftware,
      remote = remoteAddress.getAddress, // TODO using remoteName would trigger a lookup
      body = getStream(replyStream.chunkHandler)
    )
  }

  override protected def renderResponse(ctx: ChannelHandlerContext, req: SpdySynStreamFrame, response: Response): Task[List[_]] = {
    val handler = activeStreams.get(req.getStreamId)
    if (handler != null)  {
      assert(handler.isInstanceOf[SpdyReplyStream])   // Should only get requests to ReplyStreams
      handler.asInstanceOf[SpdyReplyStream].handleRequest(req, response)
    }
    else sys.error("Newly created stream doesn't exist!")
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    try {
      logger.error(s"Exception on connection with $remoteAddress", cause)
      foreachStream(_.kill(cause))
      activeStreams.clear()
      if (ctx.channel().isOpen) {  // Send GOAWAY frame to signal disconnect if we are still connected
        val goaway = new DefaultSpdyGoAwayFrame(lastOpenedStream, 2) // Internal Error
        allCatch(ctx.writeAndFlush(goaway).addListener(ChannelFutureListener.CLOSE))
      }
    } catch {    // Don't end up in an infinite loop of exceptions
      case t: Throwable =>
        val causestr = if (cause != null) cause.getStackTraceString else "NULL."
        logger.error("Caught exception in exception handling: " + causestr, t)
    }
  }

  /** Forwards messages to the appropriate SpdyStreamContext
    * @param ctx ChannelHandlerContext of this channel
    * @param msg SpdyStreamFrame to be forwarded
    */
  private def forwardMsg(ctx: ChannelHandlerContext, msg: SpdyStreamFrame) {
    val handler = activeStreams.get(msg.getStreamId)
    if (handler!= null) handler.handle(msg)
    else  {
      logger.debug(s"Received chunk on stream ${msg.getStreamId}: no handler.")
      val rst = new DefaultSpdyRstStreamFrame(msg.getStreamId, 5)  // 5: Cancel the stream
      ctx.writeAndFlush(rst)
    }
  }

  /** deal with incoming messages which belong to this service
    * @param ctx ChannelHandlerContext of the pipeline
    * @param msg received message
    */
  def onHttpMessage(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case req: SpdySynStreamFrame =>
      logger.trace(s"Received Request frame with id ${req.getStreamId}")
      setRequestStreamID(req.getStreamId)
      runHttpRequest(ctx, req)

    case p: SpdyPingFrame =>
      if (p.getId % 2 == 1) {   // Must ignore Pings with even number id
        logger.trace(s"Sending ping reply frame with id ${p.getId}")
        val ping = new DefaultSpdyPingFrame(p.getId)
        ctx.writeAndFlush(ping)
      }

    case msg: SpdyStreamFrame => forwardMsg(ctx, msg)

    case msg: SpdyWindowUpdateFrame =>
      logger.trace(s"Stream ${msg.getStreamId} received SpdyWindowUpdateFrame: $msg")
      if (msg.getStreamId == 0) updateWindow(msg.getDeltaWindowSize)  // Global window size
      else {
        val handler = activeStreams.get(msg.getStreamId)
        if (handler != null) handler.handle(msg)
        else  {
          logger.debug(s"Received chunk on stream ${msg.getStreamId}: no handler.")
          val rst = new DefaultSpdyRstStreamFrame(msg.getStreamId, 5)  // 5: Cancel the stream
          ctx.writeAndFlush(rst)
        }
      }

    case s: SpdySettingsFrame => handleSpdySettings(s)

    case msg => logger.warn("Received unknown message type: " + msg + ". Dropping.")
  }

  private def handleSpdySettings(settings: SpdySettingsFrame) {
    import SpdySettingsFrame._
    logger.trace(s"Received SPDY settings frame: $settings")

    val maxStreams = settings.getValue(SETTINGS_MAX_CONCURRENT_STREAMS)
    if (maxStreams > 0) setMaxStreams(maxStreams)

    val initWindow = settings.getValue(SETTINGS_INITIAL_WINDOW_SIZE)
    // TODO: Deal with window sizes and buffering. http://dev.chromium.org/spdy/spdy-protocol/spdy-protocol-draft3#TOC-2.6.8-WINDOW_UPDATE
    if (initWindow > 0) {
      val diff = initWindow - this.initialWindow
      setInitialWindow(initWindow)
      foreachStream(_.updateWindow(diff))
    }
  }

  private def foreachStream(f: SpdyStream => Any) {
    val it = activeStreams.values().iterator()
    while(it.hasNext) f(it.next())
  }

  // TODO: Need to implement a Spdy HttpVersion
  private def getProtocol(req: SpdySynStreamFrame) = ServerProtocol.`HTTP/1.1`
}