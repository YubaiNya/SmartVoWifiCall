package com.smartvowificall.ims.root;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import java.io.*;

/** Raw per-connection socket relay for an application already granted su access. */
public final class SocketRelayMain {
  private static void copy(InputStream in,OutputStream out) throws IOException {
    byte[] b=new byte[8192];int n;
    while((n=in.read(b))!=-1){out.write(b,0,n);out.flush();}
  }
  public static void main(String[] args) {
    int result=1;
    try {
      if(Process.myUid()!=0||args.length!=1||!args[0].matches("/data/local/tmp/vowifi/[0-9]{10,32}\\.(sock|audio\\.sock)"))throw new IllegalArgumentException();
      try(LocalSocket socket=new LocalSocket()){
        socket.connect(new LocalSocketAddress(args[0],LocalSocketAddress.Namespace.FILESYSTEM));
        Thread upload=new Thread(()->{
          try {copy(System.in,socket.getOutputStream());}
          catch(IOException ignored) { }
          finally {try{socket.close();}catch(IOException ignored){}}
        },"VoWiFi-relay-upload");
        upload.setDaemon(true);upload.start();
        copy(socket.getInputStream(),new FileOutputStream(FileDescriptor.out));
        result=0;
      }
    } catch(Throwable ignored) { }
    System.exit(result);
  }
}
