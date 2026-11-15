package com.holo.launcher.sys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.holo.launcher.notification.HoloNotificationListener

/**
 * 权限引导。
 *
 * 每个开关都"跳到系统设置让用户自己勾"，不尝试静默申请、不绕过授权。
 * jump 返回 false 表示设备上找不到该设置页，调用方应提示用户手动查找。
 */
object PermGuide {

    /** 通知读取权限（NotificationListenerService） */
    fun notification(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            fallbackAppDetail(context)
        }
    }

    /** 悬浮窗/显示在其他应用上层 */
    fun overlay(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            i.data = Uri.parse("package:" + context.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            fallbackAppDetail(context)
        }
    }

    /** 使用情况访问权限（部分 ROM 判定前台需要） */
    fun usage(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            fallbackAppDetail(context)
        }
    }

    /** 位置权限（系统权限页） */
    fun location(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + context.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 默认桌面（设置为 Launcher） */
    fun home(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_HOME_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            // 部分 ROM 无此页，退到应用详情
            fallbackAppDetail(context)
        }
    }

    /** 电池优化白名单 */
    fun battery(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            fallbackAppDetail(context)
        }
    }

    /** WiFi 设置页 */
    fun wifi(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_WIFI_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 通知读取权限是否已授予 */
    fun isNotificationGranted(context: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            )
            if (flat.isNullOrEmpty()) return false
            val cn = ComponentName(context, HoloNotificationListener::class.java)
            flat.contains(cn.flattenToString()) || flat.contains(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun fallbackAppDetail(context: Context): Boolean {
        return try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + context.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}