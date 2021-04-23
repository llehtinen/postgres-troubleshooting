package pg_troublesh;

import com.google.common.util.concurrent.RateLimiter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;

@Slf4j
public class App {

  static RateLimiter logRateLimiter = RateLimiter.create(0.05);
  static long sleepBetweenPoll = 50L;
  static long sleepBetweenMsg = 10L;
  static int msgsToRead = 3;

  @SneakyThrows
  public static void main(String[] args) {
    if (args.length < 2) {
      System.out.println("Two args required: slot_name and start_lsn [number of msgs to read]");
      System.exit(1);
    }

    if (args.length == 3) {
      msgsToRead = Integer.parseInt(args[2]);
    }

    String slot = args[0];
    String startLsn = args[1];

    log.info("DB current LSN: {}, {} flushed currently to: {}",
        getCurrentLsn(), slot, getFlushedLsn(slot));

    lsofMonitor();
    var stream = openStream(slot, startLsn);

    log.info("Replication slot {} opened with starting LSN: {}",
        slot, startLsn);

    boolean msgReceived = false;
    boolean beenWaiting = false;
    long lastMsgReceived = 0;
    long startTime = System.currentTimeMillis();
    int msgsRead = 0;

//    stream.setFlushedLSN(LogSequenceNumber.valueOf(startLsn));
//    stream.forceUpdateStatus();

    try {
      while (true) {

        ByteBuffer msg = stream.readPending();

        if (msg == null) {
          if (!msgReceived) {
            if (logRateLimiter.tryAcquire()) {
              log.info("Polling for first message");
            }
          } else {
            beenWaiting = true;
            if (logRateLimiter.tryAcquire()) {
              log.info("Polling for next message, last received {}", stream.getLastReceiveLSN().asString());
            }
          }
          TimeUnit.MILLISECONDS.sleep(sleepBetweenPoll);
          continue;
        }

        if (!msgReceived) {
          String lastLsn = stream.getLastReceiveLSN().asString();
          log.info("Received first message {} after {}", lastLsn, Duration.ofMillis(System.currentTimeMillis() - startTime));
          msgReceived = true;
        }
        if (beenWaiting) {
          String lastLsn = stream.getLastReceiveLSN().asString();
          log.info("Received message {} after waiting {}", lastLsn, Duration.ofMillis(System.currentTimeMillis() - lastMsgReceived));
          beenWaiting = false;
        }

        lastMsgReceived = System.currentTimeMillis();
        int offset = msg.arrayOffset();
        byte[] source = msg.array();
        int length = source.length - offset;
        log.info("{} {}", stream.getLastReceiveLSN(), new String(source, offset, length));
        if (msgsToRead > -1 && ++msgsRead >= msgsToRead) {
          break;
        }

        Thread.sleep(sleepBetweenMsg);

      }
    } finally {
      cxn.close();
      replCxn.close();
      lsofMonitor.destroy();
    }

  }

  static String getWalFile(String lsn) {
    return getStringResult(String.format("SELECT pg_walfile_name('%s')", lsn));
  }

  static String getCurrentLsn() {
    return getLsnAndWal("SELECT pg_current_wal_lsn()");
  }

  static String getFlushedLsn(String slotName) {
    return getLsnAndWal(String.format("SELECT confirmed_flush_lsn FROM pg_replication_slots WHERE slot_name = '%s'", slotName));
  }

  @SneakyThrows
  static String getLsnAndWal(String lsnQuery) {
    return getStringResult(lsnQuery);
  }

  @SneakyThrows
  static String getStringResult(String query) {
    try (Statement stmt = cxn.createStatement()) {
      ResultSet rs = stmt.executeQuery(query);
      rs.next();
      return rs.getString(1);
    }
  }

  @SneakyThrows
  static Connection getConnection() {
    String url = "jdbc:postgresql://localhost:5432/";
    Properties props = dbProps();
    return DriverManager.getConnection(url, props);
  }

  static Properties dbProps() {
    Properties props = new Properties();
    PGProperty.USER.set(props, "test_user");
    PGProperty.PASSWORD.set(props, "test_user");
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "13.0");
    PGProperty.REPLICATION.set(props, "database");
    PGProperty.PREFER_QUERY_MODE.set(props, "simple");
    return props;
  }

  @SneakyThrows
  static void lsofMonitor() {
    CountDownLatch processLatch = new CountDownLatch(1);
    new Thread(() -> {
      try {
        lsofMonitor = new ProcessBuilder().command("./scripts/lsof_monitor.sh").start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(lsofMonitor.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
          log.info(line);
          Thread.sleep(50);
          if (line.startsWith("Ignoring FDs")) {
            processLatch.countDown();
          }
        }
      } catch (IOException e) {
        log.error(e.getMessage());
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
    processLatch.await(1, TimeUnit.SECONDS);
  }

  @SneakyThrows
  static PGReplicationStream openStream(String slot, String startLsn) {
    return replCxn.unwrap(PGConnection.class).getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName(slot)
        .withSlotOption("include-xids", true)
        .withSlotOption("skip-empty-xacts", true)
        .withStartPosition(LogSequenceNumber.valueOf(startLsn))
        .start();
  }

  static Connection cxn = getConnection();
  static Connection replCxn = getConnection();
  static Process lsofMonitor = null;

}