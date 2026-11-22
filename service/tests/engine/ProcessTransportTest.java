package com.newvowifi.ims;

import java.io.*;

/** Process substitute only; does not launch su, a relay, or an Android process. */
public final class ProcessTransportTest {
  private static int checks;
  private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
  static final class Child extends Process {
    boolean destroyed,failOutput;
    int destroys,inputs,outputs,errors;
    final OutputStream output=new OutputStream(){
      public void write(int b){}
      public void close()throws IOException{
        check(destroyed,"blocked writer must be canceled before buffered output close");outputs++;
        if(failOutput)throw new IOException("fixture output close");
      }
    };
    final InputStream input=new ByteArrayInputStream(new byte[0]){
      public void close(){check(destroyed,"relay cancellation precedes input close");inputs++;}
    };
    final InputStream error=new ByteArrayInputStream(new byte[0]){
      public void close(){check(destroyed,"relay cancellation precedes stderr close");errors++;}
    };
    public OutputStream getOutputStream(){return output;}public InputStream getInputStream(){return input;}
    public InputStream getErrorStream(){return error;}public int waitFor(){return 0;}public int exitValue(){return 0;}
    public void destroy(){destroyed=true;destroys++;}
  }
  public static void main(String[] args)throws Exception{
    for(boolean fail:new boolean[]{false,true}){
      Child child=new Child();child.failOutput=fail;ProcessTransport transport=new ProcessTransport(child);
      check(transport.input()==child.input&&transport.output()==child.output,"raw pipe identities preserved");
      try{transport.close();check(!fail,"expected close failure");}catch(IOException expected){check(fail,"original close failure returned");}
      transport.close();
      check(child.destroys==1&&child.outputs==1&&child.inputs==1&&child.errors==1,"idempotent cancellation and all descriptors cleaned");
    }
    System.out.println("PASS "+checks+" root relay teardown checks; fake process only.");
  }
}
