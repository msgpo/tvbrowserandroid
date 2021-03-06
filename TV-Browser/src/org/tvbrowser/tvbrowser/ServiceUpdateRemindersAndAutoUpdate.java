/*
 * TV-Browser for Android
 * Copyright (C) 2018 René Mach (rene@tvbrowser.org)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to use, copy, modify or merge the Software,
 * furthermore to publish and distribute the Software free of charge without modifications and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.tvbrowser.tvbrowser;

import java.util.Date;

import org.tvbrowser.App;
import org.tvbrowser.content.TvBrowserContentProvider;
import org.tvbrowser.settings.SettingConstants;
import org.tvbrowser.utils.CompatUtils;
import org.tvbrowser.utils.IOUtils;
import org.tvbrowser.utils.PrefUtils;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class ServiceUpdateRemindersAndAutoUpdate extends Service {
  public static final String EXTRA_FIRST_STARTUP = "extraFirstStartup";
  public static final String EXTRA_UPDATE_AUTO_UPDATE = "extraUpdateAutoUpdate";

  private static final int MAX_REMINDERS = 50;
  private static final int ID_NOTIFICATION = 2;
  
  private static final String[] PROJECTION = {
      TvBrowserContentProvider.KEY_ID,
      TvBrowserContentProvider.DATA_KEY_STARTTIME
    };
  
  private Thread mUpdateRemindersThread;
  
  public ServiceUpdateRemindersAndAutoUpdate() {
  }

  @Override
  public void onCreate() {
    super.onCreate();

    if(CompatUtils.isAtLeastAndroidO()) {
      NotificationCompat.Builder b = new NotificationCompat.Builder(ServiceUpdateRemindersAndAutoUpdate.this, App.getNotificationChannelIdDefault(this));
      b.setSmallIcon(R.drawable.ic_stat_notify);
      b.setContentTitle(getResources().getText(R.string.notification_update_reminders));
      b.setCategory(NotificationCompat.CATEGORY_STATUS);

      startForeground(ID_NOTIFICATION, b.build());
    }
  }

  @Override
  public void onDestroy() {
    if(CompatUtils.isAtLeastAndroidO()) {
      stopForeground(true);
    }

    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
  
  @Override
  public int onStartCommand(final Intent intent, int flags, int startId) {
    if(intent != null && intent.getBooleanExtra(EXTRA_UPDATE_AUTO_UPDATE, false)) {
      IOUtils.handleDataUpdatePreferences(getApplicationContext());
      IOUtils.setDataTableRefreshTime(getApplicationContext());
    }

    if(mUpdateRemindersThread == null || !mUpdateRemindersThread.isAlive()) {
      mUpdateRemindersThread = new Thread("UPDATE REMINDERS THREAD") {
        @Override
        public void run() {
          if(IOUtils.isDatabaseAccessible(ServiceUpdateRemindersAndAutoUpdate.this)) {
            boolean firstStart = intent != null && intent.getBooleanExtra(EXTRA_FIRST_STARTUP, false);
            
            StringBuilder where = new StringBuilder(" ( " + TvBrowserContentProvider.DATA_KEY_MARKING_REMINDER + " OR " + TvBrowserContentProvider.DATA_KEY_MARKING_FAVORITE_REMINDER + " ) AND ( " + TvBrowserContentProvider.DATA_KEY_ENDTIME + " >= " + System.currentTimeMillis() + " ) ");
            
            if(!firstStart) {
              where.append(" AND ( ").append(TvBrowserContentProvider.DATA_KEY_STARTTIME).append(" >= ").append((System.currentTimeMillis()-200)).append(" ) ");
            }
            
            try {
              Cursor alarms = getContentResolver().query(TvBrowserContentProvider.CONTENT_URI_DATA, PROJECTION, where.toString(), null, TvBrowserContentProvider.DATA_KEY_STARTTIME + " ASC LIMIT " + MAX_REMINDERS);
              
              try {
                if(IOUtils.prepareAccess(alarms)) {
                  while(alarms.moveToNext()) {
                    long id = alarms.getLong(alarms.getColumnIndex(TvBrowserContentProvider.KEY_ID));
                    long startTime = alarms.getLong(alarms.getColumnIndex(TvBrowserContentProvider.DATA_KEY_STARTTIME));
                    
                    IOUtils.removeReminder(getApplicationContext(), id);
                    addReminder(getApplicationContext(), id, startTime, BroadcastReceiverUpdateAlarmValue.class, firstStart);
                  }
                }
              }finally {
                IOUtils.close(alarms);
              }
            }catch(Exception ise) {
              //Ignore, only make sure TV-Browser didn't crash after moving of database
            }
          }
          else {
            try {
              sleep(500);
            } catch (InterruptedException ignored) {}
          }
          
          stopSelf();
        }
      };
      mUpdateRemindersThread.start();
    }
        
    return Service.START_NOT_STICKY;
  }

  private void addReminder(Context context, long programID, long startTime, Class<?> caller, boolean firstCreation) {try {
    Logging.log(BroadcastReceiverReminder.tag, "addReminder called from: " + caller + " for programID: '" + programID + "' with start time: " + new Date(startTime), Logging.TYPE_REMINDER, context);
    
    AlarmManager alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
    
    int reminderTime = PrefUtils.getStringValueAsInt(R.string.PREF_REMINDER_TIME, R.string.pref_reminder_time_default) * 60000;
    int reminderTimeSecond = PrefUtils.getStringValueAsInt(R.string.PREF_REMINDER_TIME_SECOND, R.string.pref_reminder_time_default) * 60000;
    
    boolean remindAgain = reminderTimeSecond >= 0 && reminderTime != reminderTimeSecond;
    
    Intent remind = new Intent(context.getApplicationContext(),BroadcastReceiverReminder.class);
    remind.putExtra(SettingConstants.REMINDER_PROGRAM_ID_EXTRA, programID);
    
    if(startTime <= 0 && IOUtils.isDatabaseAccessible(context)) {
      Cursor time = null;
      try {
        time = context.getContentResolver().query(ContentUris.withAppendedId(TvBrowserContentProvider.CONTENT_URI_DATA, programID), new String[] {TvBrowserContentProvider.DATA_KEY_STARTTIME}, null, null, null);

        if(time.moveToFirst()) {
          startTime = time.getLong(0);
        }
      } finally {
        IOUtils.close(time);
      }
    }
    
    if(startTime >= System.currentTimeMillis()) {
      PendingIntent pending = PendingIntent.getBroadcast(context, (int)programID, remind, PendingIntent.FLAG_UPDATE_CURRENT);
      Intent startInfo = new Intent(context, InfoActivity.class);
      startInfo.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      startInfo.putExtra(SettingConstants.REMINDER_PROGRAM_ID_EXTRA, programID);
      
      PendingIntent start = PendingIntent.getActivity(context, (int)programID, startInfo, PendingIntent.FLAG_UPDATE_CURRENT);
      
      if(startTime-reminderTime > System.currentTimeMillis()-200) {        
        Logging.log(BroadcastReceiverReminder.tag, "Create Reminder at " + new Date(startTime-reminderTime) + " with programID: '" + programID + "' " + pending.toString(), Logging.TYPE_REMINDER, context);
        CompatUtils.setAlarm(context, alarmManager,AlarmManager.RTC_WAKEUP, startTime-reminderTime, pending, start);
      }
      else if(firstCreation) {
        Logging.log(BroadcastReceiverReminder.tag, "Create Reminder at " + new Date(System.currentTimeMillis()) + " with programID: '" + programID + "' " + pending.toString(), Logging.TYPE_REMINDER, context);
        CompatUtils.setAlarm(context, alarmManager,AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pending, start);
      }
      
      if(remindAgain && startTime-reminderTimeSecond > System.currentTimeMillis()) {
        pending = PendingIntent.getBroadcast(context, (int)-programID, remind, PendingIntent.FLAG_UPDATE_CURRENT);
        
        Logging.log(BroadcastReceiverReminder.tag, "Create Reminder at " + new Date(startTime-reminderTimeSecond) + " with programID: '-" + programID + "' " + pending.toString(), Logging.TYPE_REMINDER, context);
        CompatUtils.setAlarm(context, alarmManager,AlarmManager.RTC_WAKEUP, startTime-reminderTimeSecond, pending, start);
      }
    }
    else {
      Logging.log(BroadcastReceiverReminder.tag, "Reminder for programID: '" + programID + "' not created, starttime in past: " + new Date(startTime) + " of now: " + new Date(System.currentTimeMillis()), Logging.TYPE_REMINDER, context);
    }
  }catch(Throwable t) {t.printStackTrace();}
  }
    
  public static void startReminderUpdate(Context context) {
    startReminderUpdate(context,false);
  }
  
  private static void startReminderUpdate(Context context, boolean firstStart) {
    context = context.getApplicationContext();

    startReminderUpdate(context,false,-1);
  }
  
  private static void startReminderUpdate(Context context, boolean firstStart, long ignoreId) {
    context = context.getApplicationContext();

    Intent updateAlarms = new Intent(context, ServiceUpdateRemindersAndAutoUpdate.class);
    updateAlarms.putExtra(ServiceUpdateRemindersAndAutoUpdate.EXTRA_FIRST_STARTUP, firstStart);
    CompatUtils.startForegroundService(context, updateAlarms);
  }
}
