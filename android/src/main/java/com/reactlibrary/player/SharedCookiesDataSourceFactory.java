package com.reactlibrary.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.util.Map;
import java.util.HashMap;
import com.facebook.react.bridge.ReactContext;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;

import okhttp3.Cookie;

public class SharedCookiesDataSourceFactory implements DataSource.Factory {
  private final Uri mUri;
  private final DataSource.Factory mDataSourceFactory;
  private final ReactContext mReactContext;

  private static final String TAG = "PakExo";

  public SharedCookiesDataSourceFactory(Uri uri, ReactContext reactApplicationContext, String userAgent) {
    if (uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
      mDataSourceFactory = new DefaultHttpDataSourceFactory(userAgent);
    } else {

      DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
      Map<String, String> requestHeaders = new HashMap<String, String>();

      mDataSourceFactory = DataSourceUtil.getDefaultDataSourceFactory(reactApplicationContext, bandwidthMeter,
          requestHeaders);
    }
    mReactContext = reactApplicationContext;
    mUri = uri;
  }

  @Override
  public DataSource createDataSource() {
    DataSource dataSource = mDataSourceFactory.createDataSource();
    if (dataSource instanceof HttpDataSource) {
      // setDataSourceCookies((HttpDataSource) dataSource, mUri);
    }
    return dataSource;
  }

  private String cookieToString(Cookie cookie) {
    return cookie.name() + "=" + cookie.value() + "; ";
  }
}
