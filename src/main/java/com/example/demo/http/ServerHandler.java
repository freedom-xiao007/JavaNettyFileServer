package com.example.demo.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.SystemPropertyUtil;

import javax.activation.MimetypesFileTypeMap;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_0;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * HTTP文件服务器具体处理
 * @author lw
 */
public class ServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");
    private static final Pattern ALLOWED_FILE_NAME = Pattern.compile("[^-._]?[^<>&\"]*");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        // 检测request是否符合规范，传入的路径是否正确
        HttpResponseStatus status;
        status = checkRequest(request);
        if (!HttpResponseStatus.OK.equals(status)) {
            sendError(ctx, status, keepAlive);
            return;
        }

        String uri = request.uri();
        uri = uri.replace('/', File.separatorChar);
        String path = SystemPropertyUtil.get("user.dir") + File.separator + uri;

        File file = new File(path);
        if (file.isHidden() || !file.exists()) {
            sendError(ctx, NOT_FOUND, keepAlive);
            return;
        }

        // 如果是文件夹，返回文件夹内容
        if (file.isDirectory()) {
            sendListing(ctx, file, path, keepAlive);
            return;
        }

        if (!file.isFile()) {
            sendError(ctx, FORBIDDEN, keepAlive);
            return;
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException ignore) {
            sendError(ctx, NOT_FOUND, keepAlive);
            return;
        }

        // 发送文件
        sendFile(ctx, file, raf, keepAlive, request.protocolVersion());
    }

    private void sendListing(ChannelHandlerContext ctx, File dir, String dirPath, boolean keepAlive) {
        StringBuilder buf = new StringBuilder()
                .append("<!DOCTYPE html>\r\n")
                .append("<html><head><meta charset='utf-8' /><title>")
                .append("Listing of: ")
                .append(dirPath)
                .append("</title></head><body>\r\n")

                .append("<h3>Listing of: ")
                .append(dirPath)
                .append("</h3>\r\n")

                .append("<ul>")
                .append("<li><a href=\"../\">..</a></li>\r\n");

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f: files) {
                if (f.isHidden() || !f.canRead()) {
                    continue;
                }

                String name = f.getName();
                if (!ALLOWED_FILE_NAME.matcher(name).matches()) {
                    continue;
                }

                buf.append("<li><a href=\"")
                        .append(name)
                        .append("\">")
                        .append(name)
                        .append("</a></li>\r\n");
            }
        }

        buf.append("</ul></body></html>\r\n");

        ByteBuf buffer = ctx.alloc().buffer(buf.length());
        buffer.writeCharSequence(buf.toString(), CharsetUtil.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, buffer);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");

        this.sendAndCleanupConnection(ctx, response, keepAlive);
    }

    private void sendFile(ChannelHandlerContext ctx, File file, RandomAccessFile raf, boolean keepAlive,
                          HttpVersion requestVersion) throws IOException {
        long fileLength = raf.length();

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        HttpUtil.setContentLength(response, fileLength);
        setContentTypeHeader(response, file);

        if (!keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        } else if (requestVersion.equals(HTTP_1_0)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        // write the initial line and header
        ctx.write(response);

        // write the content
        ChannelFuture sendFileFuture;
        ChannelFuture lastContentFuture;
        if (ctx.pipeline().get(SslHandler.class) == null) {
            DefaultFileRegion fileRegion = new DefaultFileRegion(raf.getChannel(), 0, fileLength);
            sendFileFuture = ctx.write(fileRegion, ctx.newProgressivePromise());
            lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } else {
            HttpChunkedInput chunkedInput = new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192));
            sendFileFuture = ctx.writeAndFlush(chunkedInput, ctx.newProgressivePromise());
            lastContentFuture = sendFileFuture;
        }

        sendFileFuture.addListeners(new ChannelProgressiveFutureListener() {
            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
                if (total < 0) {
                    System.err.println(future.channel() + " Transfer progress: " + progress);
                } else {
                    System.err.println(future.channel() + " Transfer progress: " + progress + "/" + total);
                }
            }

            @Override
            public void operationComplete(ChannelProgressiveFuture future) {
                System.err.println(future.channel() + " Transfer complete.");
            }
        });

        if (!keepAlive) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 添加此设置才能触发文件下载，不然浏览器直接打开浏览
     */
    private void setContentTypeHeader(HttpResponse response, File file) {
        MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap();
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeTypesMap.getContentType(file.getPath()));
    }

    /**
     * 返回错误码
     * @param ctx channel
     * @param status status
     * @param keepAlive keep alive
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, boolean keepAlive) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status,
                Unpooled.copiedBuffer("Failure:" + status + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        sendAndCleanupConnection(ctx, response, keepAlive);
    }

    /**
     * 清理连接
     * 如果不是长连接，发送完毕后关闭自身
     * @param ctx channel
     * @param response response
     * @param keepAlive keep alive
     */
    private void sendAndCleanupConnection(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        if (!keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        } else if (response.protocolVersion().equals(HTTP_1_0)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        ChannelFuture flushPromise = ctx.writeAndFlush(response);
        if (!keepAlive) {
            flushPromise.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 检查request是否符合规范
     * @param request request
     * @return status
     */
    private HttpResponseStatus checkRequest(FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            return BAD_REQUEST;
        }
        if (!GET.equals(request.method())) {
            return METHOD_NOT_ALLOWED;
        }
        if (!checkUri(request.uri())) {
            return FORBIDDEN;
        }
        return OK;
    }

    /**
     * 检测request的uri是否符合规范
     * @param uri uri
     * @return bool
     */
    private boolean checkUri(String uri) {
        uri = URLDecoder.decode(uri, StandardCharsets.UTF_8);

        uri = uri.replace('/', File.separatorChar);
        return !uri.contains(File.separator + '.') && !uri.contains('.' + File.separator) && uri.charAt(0) != '.' &&
                uri.charAt(uri.length() - 1) != '.' && !INSECURE_URI.matcher(uri).matches();
    }
}
