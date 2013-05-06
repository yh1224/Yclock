package net.assemble.yclock;

import java.util.Calendar;
import java.text.DateFormat;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Vibrator;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import net.assemble.yclock.R;
import net.assemble.yclock.preferences.YclockPreferences;

/**
 * 時刻読み上げ処理
 */
public class YclockVoice {
    private static final int NOTIFICATIONID_ICON = 1;
    private static final int RESTORE_VOLUME_RETRIES = 5;
    private static final int RESTORE_VOLUME_RETRY_INTERVAL = 1000; /* ms */

    private static MediaPlayer g_Mp; // 再生中のMediaPlayer
    private static boolean g_Icon;       // ステータスバーアイコン状態

    private AudioManager mAudioManager;
    private AlarmManager mAlarmManager;
    private Context mCtx;
    private Calendar mCal;

    private int origVol;
    private int newVol;
    private int retryRestore;

    /**
     * Constructor
     *
     * @param context
     */
    public YclockVoice(Context context) {
        mCtx = context;
        mAudioManager = (AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
        mAlarmManager = (AlarmManager) mCtx.getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * MediaPlayer生成
     * 着信音量をMediaPlayerに設定する。
     *
     * @param mp 設定するMediaPlayer
     */
    private MediaPlayer createMediaPlayer(int resid) {
        // 再生中の音声があれば停止する。
        if (g_Mp != null) {
            g_Mp.stop();
            g_Mp.release();
            g_Mp = null;
        }

        // 生成
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(mCtx, Uri.parse("android.resource://" + mCtx.getPackageName() + "/" + resid));
            mp.setAudioStreamType(AudioManager.STREAM_ALARM);
            mp.prepare();
        } catch (Exception e) {
            Log.e(Yclock.TAG, "Failed to create MediaPlayer!");
            return null;
        }
        g_Mp = mp;
        return mp;
    }

    /**
     * 時報再生
     *
     * @param cal
     *            再生日時
     */
    public void play(Calendar cal) {
        mCal = cal;

        // バイブレーション
        if (YclockPreferences.getVibrate(mCtx)) {
            Vibrator vibrator = (Vibrator) mCtx.getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern;
            if (cal.get(Calendar.MINUTE) < 30) {
                pattern = new long[] {500, 200, 100, 200, 500, 200, 100, 200};
            } else {
                pattern = new long[] {500, 200, 100, 200};
            }
            vibrator.vibrate(pattern, -1);

        //  // Receiverからは直接振動させられないため、Notificationを経由する
        //  // ->そんなことはなかった
        //  NotificationManager notificationManager = (NotificationManager) mCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        //  Notification notification = new Notification();
        //  notification.vibrate = pattern;
        //  notificationManager.notify(R.string.app_name, notification);
        }

        if (YclockPreferences.getSilent(mCtx) &&
                mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
            return;
        }

        MediaPlayer mp = createMediaPlayer(getHourSound(mCal));
        if (mp == null) {
            return;
        }
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                mp.release();
                g_Mp = null;

                MediaPlayer mp2 = createMediaPlayer(getMinSound(mCal));
                if (mp2 == null) {
                    restoreVolume();
                    return;
                }
                mp2.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        restoreVolume();
                        mp.release();
                        g_Mp = null;
                    }
                });
                mp2.start();
            }
        });
        origVol = mAudioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        newVol = YclockPreferences.getVolume(mCtx);
        retryRestore = RESTORE_VOLUME_RETRIES;
        if (Yclock.DEBUG) Log.d(Yclock.TAG, "Changing alarm volume: " + origVol + " -> " + newVol);
        mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, newVol, 0);
        mp.start();
    }

    /**
     * 音量を元に戻す
     */
    private void restoreVolume() {
        if (mAudioManager.getStreamVolume(AudioManager.STREAM_ALARM) == newVol) {
            // 音量が自分で変更したものと同じ場合のみ復元する
            mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM, origVol, 0);
            if (Yclock.DEBUG) Log.d(Yclock.TAG, "Restored alarm volume: " + newVol + " -> " + origVol);
        } else {
            // 音量が他の要因により変更されていた場合、ちょっと時間を置いてリトライしてみる
            retryRestore--;
            if (retryRestore > 0) {
                if (Yclock.DEBUG) Log.d(Yclock.TAG, "Pending restoring alarm volume: count=" + retryRestore);
                //1.初回実行
                final Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restoreVolume();
                    }
                }, RESTORE_VOLUME_RETRY_INTERVAL);
            }
        }
    }

    /**
     * 現在日時の時報再生
     */
    public void play() {
        Calendar cal = Calendar.getInstance();
        if (YclockPreferences.issetHour(mCtx, cal)) {
            play(cal);
        }
    }

    /**
     * 時報テスト再生
     */
    public void playTest() {
        if (g_Mp != null) {
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 15);
        play(cal);
    }

    /**
     * 現在時刻から「時」の音声リソース取得
     *
     * @param cal
     *            日時
     * @return 「時」の音声リソースID
     */
    private static int getHourSound(Calendar cal) {
        int h = cal.get(Calendar.HOUR);
        switch (h) {
        case 0:
        case 12:
            return R.raw.h00;
        case 1:
        case 13:
            return R.raw.h01;
        case 2:
        case 14:
            return R.raw.h02;
        case 3:
        case 15:
            return R.raw.h03;
        case 4:
        case 16:
            return R.raw.h04;
        case 5:
        case 17:
            return R.raw.h05;
        case 6:
        case 18:
            return R.raw.h06;
        case 7:
        case 19:
            return R.raw.h07;
        case 8:
        case 20:
            return R.raw.h08;
        case 9:
        case 21:
            return R.raw.h09;
        case 10:
        case 22:
            return R.raw.h10;
        case 11:
        case 23:
            return R.raw.h11;
        }
        return R.raw.h00;
    }

    /**
     * 現在時刻から「分」の音声リソース取得
     *
     * @param cal
     *            日時
     * @return 「分」の音声リソースID
     */
    private static int getMinSound(Calendar cal) {
        int m = cal.get(Calendar.MINUTE);
        if (m >= 30) {
            return R.raw.m30;
        } else {
            return R.raw.m00;
        }
    }

    /**
     * ノーティフィケーションバーにアイコンを表示
     */
    private void showNotification() {
        if (g_Icon != false) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager)mCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = new Notification(R.drawable.icon, mCtx.getResources().getString(R.string.app_name), System.currentTimeMillis());
        Intent intent = new Intent(mCtx, YclockActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(mCtx, 0, intent, Intent.FLAG_ACTIVITY_NEW_TASK);
        notification.setLatestEventInfo(mCtx, mCtx.getResources().getString(R.string.app_name), mCtx.getResources().getString(R.string.app_description), contentIntent);
        notification.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        notificationManager.notify(NOTIFICATIONID_ICON, notification);
        g_Icon = true;
    }

    /**
     * ノーティフィケーションバーのアイコンを消去
     */
    private void clearNotification() {
        if (g_Icon == false) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager)mCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATIONID_ICON);
        g_Icon = false;
    }

    /**
     * タイマ設定
     *
     * @param cal
     *            設定日時
     */
    public void setAlarm(Calendar cal, long interval) {
        mAlarmManager.cancel(pendingIntent());
        long next = cal.getTimeInMillis();
        next -= (next % 1000);
        mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP, next, interval,
                pendingIntent());
        Log.d(Yclock.TAG, "set alarm: "
                + DateFormat.getDateTimeInstance().format(cal.getTime())
                + " (msec=" + next + ", interval=" + interval + ")");
    }

    /**
     * 設定に従ってタイマを設定
     */
    public void setAlarm() {
        long interval;
        Calendar cal = Calendar.getInstance();
        if (YclockPreferences.getPeriod(mCtx).equals(YclockPreferences.PREF_PERIOD_EACHHOUR)) {
            // each hour
            cal.set(Calendar.MINUTE, 0);
            cal.add(Calendar.HOUR, 1);
            interval = 60 * 60 * 1000/*AlarmManager.INTERVAL_HOUR*/;
        } else {
            // each 30min.
            if (cal.get(Calendar.MINUTE) >= 30) {
                cal.set(Calendar.MINUTE, 0);
                cal.add(Calendar.HOUR, 1);
            } else {
                cal.set(Calendar.MINUTE, 30);
            }
            interval = 30 * 60 * 1000/*AlarmManager.INTERVAL_HALF_HOUR*/;
        }
        cal.set(Calendar.SECOND, 0);
        setAlarm(cal, interval);

        if (YclockPreferences.getNotificationIcon(mCtx)) {
            showNotification();
        } else {
            clearNotification();
        }
    }

    /**
     * タイマ解除
     */
    public void resetAlarm() {
        mAlarmManager.cancel(pendingIntent());
        Log.d(Yclock.TAG, "alarm canceled.");
        clearNotification();
    }

    /**
     * PendingIntent取得
     */
    public PendingIntent pendingIntent() {
        Intent intent = new Intent(mCtx, YclockAlarmReceiver.class);
        PendingIntent sender = PendingIntent.getBroadcast(mCtx, 0, intent, 0);
        return sender;
    }
}
