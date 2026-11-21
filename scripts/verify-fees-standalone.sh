#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/Verify.java" <<'JAVA'
import com.elepay.fees.FeeSchedule;
import java.util.Random;
class Verify {
  static int count;
  static void eq(long expected,long actual){if(expected!=actual)throw new AssertionError(expected+" != "+actual); count++;}
  static void rejects(Runnable r){try{r.run();throw new AssertionError("should reject");}catch(IllegalArgumentException e){count++;}}
  public static void main(String[] args){
    var random=new Random(520);
    for(int i=0;i<10000;i++){
      long a=random.nextLong(500_000_000L)+1;
      int bps=random.nextInt(10001);
      long fixed=random.nextInt(1000);
      var p=FeeSchedule.configured("legacy",fixed,Long.MAX_VALUE,bps,"");
      eq(a*bps/10000+fixed,FeeSchedule.calculateFloor(p,a));
      long cap=random.nextLong(100000);
      var tier=FeeSchedule.configured("tier",fixed,cap,0,"10000:100,*:50");
      eq(Math.min(cap,(Math.min(a,10000)*100+Math.max(0,a-10000)*50)/10000+fixed),FeeSchedule.calculateFloor(tier,a));
    }
    var p=FeeSchedule.configured("tiny",0,Long.MAX_VALUE,1,"");
    eq(0,FeeSchedule.calculate(p,4999)); eq(1,FeeSchedule.calculate(p,5000));
    eq(922337203685478L,FeeSchedule.calculate(p,Long.MAX_VALUE));
    rejects(()->FeeSchedule.calculate(p,-1));
    rejects(()->FeeSchedule.configured("bad",0,100,0,"100:20"));
    rejects(()->FeeSchedule.configured("bad",0,100,0,"100:20,100:50,*:1"));
    System.out.println("PASS "+count+" fee assertions, 10000 randomized input sets");
  }
}
JAVA
javac -d "$work" src/main/java/com/elepay/fees/FeeSchedule.java "$work/Verify.java"
java -cp "$work" Verify
