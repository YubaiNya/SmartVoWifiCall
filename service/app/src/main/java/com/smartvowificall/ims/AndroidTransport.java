package com.smartvowificall.ims;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import com.smartvowificall.ims.engine.EngineRpcClient;
import java.io.*;
final class AndroidTransport implements EngineRpcClient.TransportFactory {
  private final String apk;
  AndroidTransport() { this(null); }
  AndroidTransport(String apk) { this.apk=apk; }
  public EngineRpcClient.Transport open(String path, int timeout) throws IOException {
    LocalSocket socket = new LocalSocket();
    try {
      socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
    } catch (IOException e) {
      socket.close();
      // Before a reboot installs the priv-app mount, the normal app domain cannot
      // connect to a root socket. Use its already-approved su permission and pipes;
      // do not broaden SELinux permissions for all third-party applications.
      if(apk!=null && android.os.Process.myUid()!=0 && isPermissionFailure(e)) return rootPipe(path);
      throw e;
    }
    return new EngineRpcClient.Transport() {
      public InputStream input() throws IOException {
        return socket.getInputStream();
      }
      public OutputStream output() throws IOException {
        return socket.getOutputStream();
      }
      public void close() throws IOException {
        socket.close();
      }
    };
  }
  private static boolean isPermissionFailure(Throwable e) {
    for(Throwable t=e;t!=null;t=t.getCause())
      if(t instanceof android.system.ErrnoException && ((android.system.ErrnoException)t).errno==android.system.OsConstants.EACCES)return true;
    return e.getMessage()!=null && (e.getMessage().contains("EACCES")||e.getMessage().contains("Permission denied"));
  }
  private EngineRpcClient.Transport rootPipe(String path) throws IOException {
    if(!path.matches("/data/local/tmp/vowifi/[0-9]{10,32}\\.(sock|audio\\.sock)"))throw new IOException("INVALID_ENGINE_SOCKET");
    String command="export CLASSPATH="+RootSupervisor.quote(apk)+"; exec /system/bin/app_process /system/bin com.smartvowificall.ims.root.SocketRelayMain "+RootSupervisor.quote(path);
    Process child=new ProcessBuilder("su","-W","-c",command).start();
    Thread errors=new Thread(()->{try(InputStream in=child.getErrorStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}catch(IOException ignored){}},"VoWiFi-root-stderr");
    errors.setDaemon(true);errors.start();
    return new ProcessTransport(child);
  }
}
