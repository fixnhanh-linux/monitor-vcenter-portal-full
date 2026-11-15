package com.vmmonitor.util;

import com.vmmonitor.util.*;
import com.vmmonitor.config.*;
import com.vmmonitor.store.*;
import com.vmmonitor.customer.*;
import com.vmmonitor.security.*;
import com.vmmonitor.web.*;
import com.vmmonitor.alert.*;
import com.vmmonitor.infra.*;
import com.vmmonitor.ops.*;
import com.vmmonitor.admin.*;
import com.vmmonitor.core.*;

/** Lỗi trả về client với mã HTTP (giống Object.assign(new Error(), {status}) của bản Node). */
public class HttpError extends RuntimeException {
  public final int status;
  public HttpError(int status, String message) { super(message); this.status = status; }
}
