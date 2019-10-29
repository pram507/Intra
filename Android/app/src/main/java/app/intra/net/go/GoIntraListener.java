/*
Copyright 2019 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.net.go;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.collection.LongSparseArray;
import app.intra.net.dns.DnsPacket;
import app.intra.net.doh.Transaction;
import app.intra.net.doh.Transaction.Status;
import app.intra.sys.IntraVpnService;
import app.intra.sys.Names;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.perf.FirebasePerformance;
import com.google.firebase.perf.metrics.HttpMetric;
import doh.Doh;
import doh.Token;
import intra.TCPSocketSummary;
import intra.UDPSocketSummary;
import java.net.ProtocolException;
import java.util.Calendar;
import split.RetryStats;

/**
 * This is a callback class that is passed to our go-tun2socks code.  Go calls this class's methods
 * when a socket has concluded, with performance metrics for that socket, and this class forwards
 * those metrics to Firebase.
 */
public class GoIntraListener implements tunnel.IntraListener {

  // UDP is often used for one-off messages and pings.  The relative overhead of reporting metrics
  // on these short messages would be large, so we only report metrics on sockets that transfer at
  // least this many bytes.
  private static final int UDP_THRESHOLD_BYTES = 10000;

  private final IntraVpnService vpnService;
  GoIntraListener(IntraVpnService vpnService) {
    this.vpnService = vpnService;
  }

  @Override
  public void onTCPSocketClosed(TCPSocketSummary summary) {
    // We had a functional socket long enough to record statistics.
    // Report the BYTES event :
    // - UPLOAD: total bytes uploaded over the lifetime of a socket
    // - DOWNLOAD: total bytes downloaded
    // - PORT: TCP port number (i.e. protocol type)
    // - TCP_HANDSHAKE_MS: TCP handshake latency in milliseconds
    // - DURATION: socket lifetime in seconds
    // - TODO: FIRST_BYTE_MS: Time between socket open and first byte from server, in milliseconds.

    Bundle bytesEvent = new Bundle();
    bytesEvent.putLong(Names.UPLOAD.name(), summary.getUploadBytes());
    bytesEvent.putLong(Names.DOWNLOAD.name(), summary.getDownloadBytes());
    bytesEvent.putInt(Names.PORT.name(), summary.getServerPort());
    bytesEvent.putInt(Names.TCP_HANDSHAKE_MS.name(), summary.getSynack());
    bytesEvent.putInt(Names.DURATION.name(), summary.getDuration());
    FirebaseAnalytics.getInstance(vpnService).logEvent(Names.BYTES.name(), bytesEvent);

    RetryStats retry = summary.getRetry();
    if (retry != null) {
      // Connection was eligible for split-retry.
      if (retry.getSplit() == 0) {
        // Split-retry was not attempted.
        return;
      }
      // Prepare an EARLY_RESET event to collect metrics on success rates for splitting:
      // - BYTES : Amount uploaded before reset
      // - CHUNKS : Number of upload writes before reset
      // - TIMEOUT : Whether the initial connection failed with a timeout.
      // - SPLIT : Number of bytes included in the first retry segment
      // - RETRY : 1 if retry succeeded, otherwise 0
      Bundle resetEvent = new Bundle();
      resetEvent.putInt(Names.BYTES.name(), retry.getBytes());
      resetEvent.putInt(Names.CHUNKS.name(), retry.getChunks());
      resetEvent.putInt(Names.TIMEOUT.name(), retry.getTimeout() ? 1 : 0);
      resetEvent.putInt(Names.SPLIT.name(), retry.getSplit());
      boolean success = summary.getDownloadBytes() > 0;
      resetEvent.putInt(Names.RETRY.name(), success ? 1 : 0);
      FirebaseAnalytics.getInstance(vpnService).logEvent(Names.EARLY_RESET.name(), resetEvent);
    }
 }

  @Override
  public void onUDPSocketClosed(UDPSocketSummary summary) {
    long totalBytes = summary.getUploadBytes() + summary.getDownloadBytes();
    if (totalBytes < UDP_THRESHOLD_BYTES) {
      return;
    }
    Bundle event = new Bundle();
    event.putLong(Names.UPLOAD.name(), summary.getUploadBytes());
    event.putLong(Names.DOWNLOAD.name(), summary.getDownloadBytes());
    event.putLong(Names.DURATION.name(), summary.getDuration());
    FirebaseAnalytics.getInstance(vpnService).logEvent(Names.UDP.name(), event);
  }

  private static final LongSparseArray<Status> goStatusMap = new LongSparseArray<>();
  static {
    goStatusMap.put(Doh.Complete, Status.COMPLETE);
    goStatusMap.put(Doh.SendFailed, Status.SEND_FAIL);
    goStatusMap.put(Doh.HTTPError, Status.HTTP_ERROR);
    goStatusMap.put(Doh.BadQuery, Status.INTERNAL_ERROR); // TODO: Add a BAD_QUERY Status
    goStatusMap.put(Doh.BadResponse, Status.BAD_RESPONSE);
    goStatusMap.put(Doh.InternalError, Status.INTERNAL_ERROR);
  }

  // Wrapping HttpMetric into a doh.Token allows us to get paired query and response notifications
  // from Go without reverse-binding any Java APIs into Go.  Pairing these notifications is
  // required by the structure of the HttpMetric API (which does not have any other way to record
  // latency), and reverse binding is worth avoiding, especially because it's not compatible with
  // the Go module system (https://github.com/golang/go/issues/27234).
  private class Metric implements doh.Token {
    final HttpMetric metric;
    Metric(String url) {
      metric = FirebasePerformance.getInstance().newHttpMetric(url, "POST");
    }
  }

  @Override
  public Token onQuery(String url) {
    Metric m = new Metric(url);
    m.metric.start();
    return m;
  }

  private static int len(byte[] a) {
    return a != null ? a.length : 0;
  }

  @Override
  public void onResponse(Token token, doh.Summary summary) {
    if (summary.getHTTPStatus() != 0 && token != null) {
      // HTTP transaction completed.  Report performance metrics.
      Metric m = (Metric)token;
      m.metric.setRequestPayloadSize(len(summary.getQuery()));
      m.metric.setHttpResponseCode((int)summary.getHTTPStatus());
      m.metric.setResponsePayloadSize(len(summary.getResponse()));
      m.metric.stop();  // Finalizes the metric and queues it for upload.
    }

    final DnsPacket query;
    try {
      query = new DnsPacket(summary.getQuery());
    } catch (ProtocolException e) {
      return;
    }
    long latencyMs = (long)(1000 * summary.getLatency());
    long nowMs = SystemClock.elapsedRealtime();
    long queryTimeMs = nowMs - latencyMs;
    Transaction transaction = new Transaction(query, queryTimeMs);
    transaction.response = summary.getResponse();
    transaction.responseTime = (long)(1000 * summary.getLatency());
    transaction.serverIp = summary.getServer();
    transaction.status = goStatusMap.get(summary.getStatus());
    transaction.responseCalendar = Calendar.getInstance();

    vpnService.recordTransaction(transaction);
  }
}
