package play.core.server.netty

import org.jboss.netty.buffer._
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap._
import org.jboss.netty.channel.Channels._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel.socket.nio._
import org.jboss.netty.handler.stream._
import org.jboss.netty.handler.codec.http.HttpHeaders._
import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import org.jboss.netty.handler.codec.http.HttpHeaders.Values._
import org.jboss.netty.handler.codec.http.websocket.DefaultWebSocketFrame
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrame
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrameDecoder
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrameEncoder

import org.jboss.netty.channel.group._
import java.util.concurrent._

import play.core._
import play.core.server.websocket._
import play.api._
import play.api.mvc._
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.libs.concurrent._

import scala.collection.JavaConverters._

private[server] trait WebSocketHandler {
  def newWebSocketInHandler[A](frameFormatter: play.api.mvc.WebSocket.FrameFormatter[A]) = {

    val nettyFrameFormatter = frameFormatter.asInstanceOf[play.core.server.websocket.FrameFormatter[A]]

    val enumerator = new Enumerator[A] {
      val iterateeAgent = Agent[Option[Iteratee[A, Any]]](None)
      private val promise: Promise[Iteratee[A, Any]] with Redeemable[Iteratee[A, Any]] = Promise[Iteratee[A, Any]]()

      def apply[R](i: Iteratee[A, R]) = {
        iterateeAgent.send(_.orElse(Some(i.asInstanceOf[Iteratee[A, Any]])))
        promise.asInstanceOf[Promise[Iteratee[A, R]]]
      }

      def frameReceived(ctx: ChannelHandlerContext, input: Input[A]) {
        iterateeAgent.send(iteratee =>
          iteratee.map(it => it.flatFold(
            (a, e) => { sys.error("Getting messages on a supposedly closed socket? frame: " + input) },
            k => {
              val next = k(input)
              next.fold(
                (a, e) => {
                  ctx.getChannel().disconnect();
                  iterateeAgent.close();
                  promise.redeem(next);
                  println("cleaning for channel " + ctx.getChannel());
                  Promise.pure(next)
                },
                _ => Promise.pure(next),
                (msg, e) => { /* deal with error, maybe close the socket */ Promise.pure(next) })
            },
            (err, e) => /* handle error, maybe close the socket */ Promise.pure(it))))
      }
    }

    (enumerator,
      new SimpleChannelUpstreamHandler {

        override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
          e.getMessage match {
            case frame: Frame if nettyFrameFormatter.fromFrame.isDefinedAt(frame) => {
              enumerator.frameReceived(ctx, El(nettyFrameFormatter.fromFrame(frame)))
            }
            case frame: CloseFrame => enumerator.frameReceived(ctx, EOF)
            case frame: Frame => //
            case _ => //
          }
        }

        override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
          e.getCause().printStackTrace();
          e.getChannel().close();
        }

        override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
          enumerator.frameReceived(ctx, EOF)
          println("disconnecting socket")
          println("disconnected socket")
        }

      })

  }

  def isWebSocket(request: HttpRequest) =
    HttpHeaders.Values.UPGRADE.equalsIgnoreCase(request.getHeader(CONNECTION)) &&
      HttpHeaders.Values.WEBSOCKET.equalsIgnoreCase(request.getHeader(HttpHeaders.Names.UPGRADE))

  def websocketHandshake[A](ctx: ChannelHandlerContext, req: HttpRequest, e: MessageEvent)(frameFormatter: play.api.mvc.WebSocket.FrameFormatter[A]): Enumerator[A] = {

    WebSocketHandshake.shake(ctx, req)

    val (enumerator, handler) = newWebSocketInHandler(frameFormatter)
    val p: ChannelPipeline = ctx.getChannel().getPipeline();
    p.replace("handler", "handler", handler);

    enumerator
  }

}