package com.p2p.chat.transport;

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serial transport over a COM/RFCOMM device, used for Bluetooth SPP.
 *
 * <p>On the OS level Bluetooth classic presents itself as a serial port:
 * <ul>
 *   <li><b>Linux:</b> after pairing, set up the channel with
 *       {@code sudo rfcomm listen /dev/rfcomm0 1} (server side) or
 *       {@code sudo rfcomm connect /dev/rfcomm0 <MAC> 1} (client side), then open
 *       {@code /dev/rfcomm0}.</li>
 *   <li><b>Windows:</b> pair the device, add an outgoing Bluetooth COM port, and
 *       open the assigned {@code COM#}.</li>
 * </ul>
 * Baud rate is irrelevant for RFCOMM but required by the serial API, so 115200
 * is used as a harmless default.
 */
public final class SerialTransport implements Transport {
    private final SerialPort port;

    public SerialTransport(String portName) throws IOException {
        this.port = SerialPort.getCommPort(portName);
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
        if (!port.openPort()) {
            throw new IOException("Cannot open serial port: " + portName);
        }
    }

    @Override
    public InputStream input() throws IOException {
        InputStream in = port.getInputStream();
        if (in == null) {
            throw new IOException("Serial port input stream unavailable");
        }
        return in;
    }

    @Override
    public OutputStream output() throws IOException {
        OutputStream out = port.getOutputStream();
        if (out == null) {
            throw new IOException("Serial port output stream unavailable");
        }
        return out;
    }

    @Override
    public String id() {
        return "serial:" + port.getSystemPortName();
    }

    @Override
    public void close() {
        if (port.isOpen()) {
            port.closePort();
        }
    }
}
