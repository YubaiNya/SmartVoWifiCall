import com.newvowifi.ims.policy.RegistrationRouting;
import java.util.*;

/** Actual route synchronizer with a fake per-slot resolver. No Android/phone access. */
public final class RegistrationRoutingTest {
  private static int checks;
  private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
  private static final class Resolver implements RegistrationRouting.Backend {
    String current="";
    final List<String> mutations=new ArrayList<>();
    boolean failBind,failRestore;
    public String current(){return current;}
    public void selectIndependent()throws Exception{
      if(failBind)throw new Exception("bind failed");
      mutations.add("bind");current=RegistrationRouting.OWN_PACKAGE;
    }
    public void restoreSystem()throws Exception{
      if(failRestore)throw new Exception("restore failed");
      mutations.add("restore");current="";
    }
  }
  private static RegistrationRouting.Result sync(Resolver r,boolean registered,boolean frameworkBusy,boolean engineBusy)throws Exception{
    return RegistrationRouting.synchronize(registered,frameworkBusy,engineBusy,r);
  }
  public static void main(String[] args)throws Exception{
    Resolver r=new Resolver();
    for(String phase:new String[]{"CONNECTING","REGISTERING","UNREGISTERED","DISCONNECTED"}){
      RegistrationRouting.Result result=sync(r,false,false,false);
      check(!result.independent&&!result.deferred&&r.mutations.isEmpty(),phase+" never takes MMTEL from system");
    }
    check(sync(r,true,false,false).independent,"successful registration selects independent service");
    check(r.mutations.equals(Arrays.asList("bind")),"single bind after registration");
    check(sync(r,true,false,false).independent&&r.mutations.size()==1,"steady registration does not rebind every poll");
    check(!sync(r,false,false,false).independent,"registration loss restores system even when tun0 remains UP");
    check(r.mutations.equals(Arrays.asList("bind","restore")),"loss restores exactly once");
    check(!sync(r,false,false,false).independent&&r.mutations.size()==2,"registration retry leaves system selected");
    check(sync(r,true,false,false).independent,"registration recovery promotes candidate again");
    check(r.mutations.equals(Arrays.asList("bind","restore","bind")),"full disconnect/reconnect sequence");

    r=new Resolver();r.current=RegistrationRouting.OWN_PACKAGE;
    for(boolean[] busy:new boolean[][]{{true,false},{false,true},{true,true}}){
      RegistrationRouting.Result result=sync(r,false,busy[0],busy[1]);
      check(result.independent&&result.deferred&&r.mutations.isEmpty(),"never remove provider during a framework or engine call");
    }
    check(!sync(r,false,false,false).independent&&r.mutations.size()==1,"deferred restoration runs when call ends");
    RegistrationRouting.Result delayed=sync(r,true,true,false);
    check(!delayed.independent&&delayed.deferred&&r.mutations.size()==1,"OEM call prevents takeover");
    check(sync(r,true,false,false).independent&&r.mutations.size()==2,"takeover resumes after OEM call");

    r=new Resolver();
    check(sync(r,true,false,true).independent,"candidate incoming call can attach to otherwise idle Android framework");
    check(r.mutations.size()==1,"candidate ringing does not deadlock its own attachment");
    r=new Resolver();r.current=RegistrationRouting.OWN_PACKAGE;
    check(!sync(r,false,false,false).independent,"restart with old owned override but unverified registration releases provider");

    r=new Resolver();r.current="other.carrier.ims";
    check(!sync(r,false,false,false).independent&&r.mutations.isEmpty(),"another provider is never restored by this app");
    try{sync(r,true,false,false);throw new AssertionError("conflict accepted");}
    catch(IllegalStateException expected){check(expected.getMessage().equals("OTHER_CARRIER_OVERRIDE_PRESENT"),"third-party override conflict preserved");}
    check(r.current.equals("other.carrier.ims")&&r.mutations.isEmpty(),"conflict makes no writes");
    r=new Resolver();r.current=null;
    try{sync(r,true,false,false);throw new AssertionError("unknown treated as OEM");}
    catch(IllegalStateException expected){check(expected.getMessage().equals("IMS_CONFIG_UNKNOWN"),"unknown resolver state rejected");}

    r=new Resolver();r.failBind=true;
    try{sync(r,true,false,false);throw new AssertionError("failed bind reported selected");}
    catch(Exception expected){check(r.current.isEmpty()&&r.mutations.isEmpty(),"backend failure not reported as successful takeover");}
    r=new Resolver();r.current=RegistrationRouting.OWN_PACKAGE;r.failRestore=true;
    try{sync(r,false,false,false);throw new AssertionError("failed restore reported system");}
    catch(Exception expected){check(r.current.equals(RegistrationRouting.OWN_PACKAGE)&&r.mutations.isEmpty(),"backend restore failure retained for retry");}
    System.out.println("PASS "+checks+" registration-to-provider routing assertions; no device or actual calls.");
  }
}
