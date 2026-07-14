package com.ledgerflow.orders.internal.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class LimitedBodyRequest extends HttpServletRequestWrapper {

  private final ServletInputStream inputStream;

  LimitedBodyRequest(HttpServletRequest request, long maximumBytes) throws IOException {
    super(request);
    this.inputStream = new LimitedServletInputStream(request.getInputStream(), maximumBytes);
  }

  @Override
  public ServletInputStream getInputStream() {
    return inputStream;
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
  }

  private static final class LimitedServletInputStream extends ServletInputStream {

    private final InputStream delegate;
    private final long maximumBytes;
    private long bytesRead;
    private boolean finished;

    private LimitedServletInputStream(InputStream delegate, long maximumBytes) {
      this.delegate = delegate;
      this.maximumBytes = maximumBytes;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value != -1) {
        recordBytes(1);
      } else {
        finished = true;
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int permitted = (int) Math.min(length, maximumBytes - bytesRead + 1);
      int count = delegate.read(buffer, offset, permitted);
      if (count > 0) {
        recordBytes(count);
      } else if (count == -1) {
        finished = true;
      }
      return count;
    }

    @Override
    public boolean isFinished() {
      return finished;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      throw new UnsupportedOperationException("Asynchronous request reads are not supported");
    }

    private void recordBytes(int count) throws PayloadTooLargeException {
      bytesRead += count;
      if (bytesRead > maximumBytes) {
        throw new PayloadTooLargeException();
      }
    }
  }
}
