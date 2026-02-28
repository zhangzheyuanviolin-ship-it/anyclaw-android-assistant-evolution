/**
 * bionic-compat.js - Android Bionic libc compatibility shim
 *
 * Loaded via NODE_OPTIONS="-r /path/to/bionic-compat.js"
 *
 * Based on https://github.com/AidanPark/openclaw-android
 *
 * Patches:
 * - process.platform: 'android' -> 'linux' (OpenClaw rejects 'android')
 * - os.networkInterfaces(): try-catch for Bionic getifaddrs() crashes
 * - os.cpus(): fallback for empty array (Android /proc/cpuinfo restriction)
 */

'use strict';

Object.defineProperty(process, 'platform', {
  value: 'linux',
  writable: false,
  enumerable: true,
  configurable: true,
});

const os = require('os');

const _originalCpus = os.cpus;
os.cpus = function cpus() {
  const result = _originalCpus.call(os);
  if (result.length > 0) return result;
  return [{ model: 'unknown', speed: 0, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } }];
};

const _originalNetworkInterfaces = os.networkInterfaces;
os.networkInterfaces = function networkInterfaces() {
  try {
    return _originalNetworkInterfaces.call(os);
  } catch {
    return {
      lo: [{
        address: '127.0.0.1',
        netmask: '255.0.0.0',
        family: 'IPv4',
        mac: '00:00:00:00:00:00',
        internal: true,
        cidr: '127.0.0.1/8',
      }],
    };
  }
};
