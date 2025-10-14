# TAP Benchmark Module

This module provides comprehensive latency measurement functionality for the TAP (Transport As Plugin) system in Signal.

## Overview

The benchmark module measures end-to-end message latency and breaks it down into component times:
- Encryption time
- Upload time to COS provider
- Polling wait time
- Download time from COS provider
- Decryption time
- UI rendering time

## Architecture

### Core Components

1. **BenchmarkRecorder**: Central recording system that captures timestamps at key lifecycle points
2. **BenchmarkConfig**: Configuration for test sessions (message count, type, size, etc.)
3. **BenchmarkReport**: Data structure and export functionality for test results
4. **BenchmarkController**: Orchestrates test execution
5. **BenchmarkMessageGenerator**: Generates test messages of various types and sizes

### UI Components

1. **BenchmarkConfigDialog**: Dialog for configuring test parameters
2. **BenchmarkMenuExtension**: Adds benchmark menu items to conversation screens

## Usage

### Building the Benchmark Variant

```bash
./gradlew assemblePlayProdBenchmark
```

### Running Tests

1. Install the benchmark APK on two devices (A and B)
2. Open a conversation between the devices
3. Tap the menu (three dots) in the conversation
4. Select "Start Benchmark Test"
5. Configure test parameters:
   - Message count (e.g., 50)
   - Message type (TEXT, IMAGE, VOICE, VIDEO, FILE, LARGE_FILE)
   - Message size (SMALL, MEDIUM, LARGE)
   - Send interval (ms)
   - Device role (SENDER, RECEIVER, BOTH)
6. Start the test
7. Wait for completion
8. Export reports from both devices

### Exporting Reports

1. After test completion, tap menu → "Export Benchmark Report"
2. Reports are saved in two formats:
   - JSON: Complete detailed data
   - CSV: Simplified for spreadsheet analysis
3. Files are saved to:
   - Internal: `/data/data/org.thoughtcrime.securesms.benchmark/files/benchmarks/`
   - External: `/Android/data/org.thoughtcrime.securesms.benchmark/files/Documents/benchmarks/`
4. Use the share dialog to send reports via email, Drive, etc.

### Retrieving Reports via ADB

```bash
# List reports
adb shell run-as org.thoughtcrime.securesms.benchmark ls files/benchmarks/

# Pull JSON report
adb shell "run-as org.thoughtcrime.securesms.benchmark cat files/benchmarks/tap_benchmark_sender_*.json" > report.json

# Pull CSV report
adb shell "run-as org.thoughtcrime.securesms.benchmark cat files/benchmarks/tap_benchmark_sender_*.csv" > report.csv
```

## Integration Points

The benchmark module integrates with TAP at these key points:

### Sender Side

1. **Message Send Click**: Record T0 when user clicks send
2. **Encryption Start/End**: In TAP encryption layer (T1, T2)
3. **Upload Start/End**: In TransportProvider upload methods (T3, T4)

### Receiver Side

4. **Poll Detection**: When poller detects new message (T5)
5. **Download Start/End**: In TransportProvider download methods (T6, T7)
6. **Decryption Start/End**: In TAP decryption layer (T8, T9)
7. **UI Display**: When message appears in conversation list (T10)

## Adding Benchmark Instrumentation

### Example: Instrumenting Upload

```kotlin
// In your TransportProvider implementation
override fun uploadFile(file: File, metadata: TransportMetadata): TransportResult {
    val messageId = metadata.messageId
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.UPLOAD_START)
    
    try {
        val result = actualUploadImplementation(file, metadata)
        BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.UPLOAD_END)
        return result
    } catch (e: Exception) {
        BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.UPLOAD_END)
        throw e
    }
}
```

### Example: Instrumenting Encryption

```kotlin
fun encryptMessage(plaintext: ByteArray, messageId: String): ByteArray {
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.ENCRYPT_START)
    val ciphertext = doEncryption(plaintext)
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.ENCRYPT_END)
    return ciphertext
}
```

## Data Analysis

### Report Structure

```json
{
  "sessionId": "1697280000000",
  "deviceRole": "SENDER",
  "config": {
    "messageCount": 50,
    "messageType": "TEXT",
    "messageSize": "SMALL",
    "intervalMs": 1000
  },
  "records": [
    {
      "messageId": "benchmark_1697280000000_0",
      "messageType": "TEXT",
      "messageSizeBytes": 150,
      "t0_sendClicked": 1697280000000,
      "t1_encryptStart": 1697280000005,
      "t2_encryptEnd": 1697280000015,
      "t3_uploadStart": 1697280000020,
      "t4_uploadEnd": 1697280000200,
      "endToEndLatency": 5432,
      "encryptionTime": 10,
      "uploadTime": 180
    }
  ],
  "statistics": {
    "avgEndToEndLatencyMs": 5234.5,
    "medianEndToEndLatencyMs": 5100.0,
    "p95EndToEndLatencyMs": 6500.0,
    "avgEncryptionTimeMs": 12.3,
    "avgUploadTimeMs": 1850.2,
    "avgPollingWaitTimeMs": 2500.0,
    "avgDownloadTimeMs": 450.1,
    "avgDecryptionTimeMs": 15.8
  }
}
```

### Analysis Scripts

You can process the JSON reports with Python/R/Excel:

```python
import json
import pandas as pd

# Load reports from both devices
with open('sender_report.json') as f:
    sender_data = json.load(f)
    
with open('receiver_report.json') as f:
    receiver_data = json.load(f)

# Merge based on message ID
# Analyze latency distributions
# Generate charts
```

## Testing Assets

To improve test realism, add pre-generated media files:

```
app/src/benchmark/assets/
├── voice_10s.m4a
├── voice_30s.m4a
├── voice_60s.m4a
├── image_100kb.jpg
├── image_500kb.jpg
├── image_2mb.jpg
├── video_5mb.mp4
├── video_20mb.mp4
└── video_50mb.mp4
```

If assets are not provided, the module will generate placeholder files.

## Performance Considerations

- Benchmark recording has minimal overhead (~0.1ms per timestamp)
- Only enabled when explicitly activated via UI
- No impact on production builds (benchmark build variant only)
- Uses ConcurrentHashMap for thread-safe recording

## Limitations

- Requires manual coordination between two devices
- Clock synchronization between devices may introduce error in cross-device measurements
- Large file tests may be slow on poor network connections
- UI automation is manual (no Espresso integration yet)

## Future Enhancements

- [ ] Automated two-device testing with Firebase Test Lab
- [ ] Real-time latency visualization during tests
- [ ] Network condition simulation (throttling)
- [ ] Automated report comparison and regression detection
- [ ] Integration with CI/CD for performance regression testing

