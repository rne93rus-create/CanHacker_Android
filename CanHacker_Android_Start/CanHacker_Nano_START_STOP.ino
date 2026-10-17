#include <SPI.h>
#include <mcp_can.h>

// Arduino Nano + MCP2515 (8 MHz crystal)
// CS=D10, INT=D2, SPI: MOSI=D11, MISO=D12, SCK=D13
const byte CAN_CS_PIN = 10;
const byte CAN_INT_PIN = 2;

MCP_CAN CAN0(CAN_CS_PIN);
String commandLine = "";
unsigned long currentBitrate = 500;
bool canReady = false;
bool streamingEnabled = false;

bool beginCAN(unsigned long bitrate) {
  byte speed;
  switch (bitrate) {
    case 125: speed = CAN_125KBPS; break;
    case 250: speed = CAN_250KBPS; break;
    case 500: speed = CAN_500KBPS; break;
    case 1000: speed = CAN_1000KBPS; break;
    default: return false;
  }

  if (CAN0.begin(MCP_ANY, speed, MCP_8MHZ) != CAN_OK) {
    canReady = false;
    return false;
  }
  CAN0.setMode(MCP_NORMAL);
  currentBitrate = bitrate;
  canReady = true;
  return true;
}

void printFrame() {
  unsigned long rxId = 0;
  byte len = 0;
  byte buf[8];
  if (CAN0.readMsgBuf(&rxId, &len, buf) != CAN_OK) return;

  Serial.print("ID=");
  Serial.print(rxId, HEX);
  Serial.print(" DLC=");
  Serial.print(len);
  Serial.print(" DATA=");
  for (byte i = 0; i < len && i < 8; i++) {
    if (buf[i] < 0x10) Serial.print('0');
    Serial.print(buf[i], HEX);
    if (i + 1 < len && i < 7) Serial.print(' ');
  }
  Serial.println();
}

void handleCommand(String cmd) {
  cmd.trim();
  cmd.toUpperCase();

  if (cmd == "START") {
    if (!canReady) {
      Serial.println("ERR CAN_NOT_READY");
    } else {
      streamingEnabled = true;
      Serial.println("STARTED");
    }
    return;
  }

  if (cmd == "STOP") {
    streamingEnabled = false;
    Serial.println("STOPPED");
    return;
  }

  if (cmd == "PING") {
    Serial.println("PONG");
    return;
  }

  if (cmd.startsWith("CAN SPEED ")) {
    long rate = cmd.substring(10).toInt();
    if (rate == 125 || rate == 250 || rate == 500 || rate == 1000) {
      bool wasStreaming = streamingEnabled;
      streamingEnabled = false;
      if (beginCAN((unsigned long)rate)) {
        Serial.print("CAN_SPEED=");
        Serial.println(currentBitrate);
        streamingEnabled = wasStreaming;
      } else {
        Serial.println("ERR CAN_INIT");
      }
    } else {
      Serial.println("ERR BAD_SPEED");
    }
    return;
  }

  if (cmd.length() > 0) Serial.println("ERR UNKNOWN_COMMAND");
}

void setup() {
  Serial.begin(115200);
  commandLine.reserve(48);
  pinMode(CAN_INT_PIN, INPUT);
  delay(300);

  if (beginCAN(500)) {
    streamingEnabled = true;  // Begin showing CAN frames immediately after startup.
    Serial.println("READY");
    Serial.print("CAN_SPEED=");
    Serial.println(currentBitrate);
  } else {
    Serial.println("ERR MCP2515_INIT");
  }
}

void loop() {
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (commandLine.length() > 0) {
        handleCommand(commandLine);
        commandLine = "";
      }
    } else if (commandLine.length() < 48) {
      commandLine += c;
    } else {
      commandLine = "";
      Serial.println("ERR COMMAND_TOO_LONG");
    }
  }

  if (streamingEnabled && canReady && CAN0.checkReceive() == CAN_MSGAVAIL) {
    printFrame();
  }
}