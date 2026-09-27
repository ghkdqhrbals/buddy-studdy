#!/usr/bin/env python3
"""Capture actual BuddyStudy DEBUG fixtures from an installed, booted iOS simulator.
Example: python3 capture-native.py UDID iphone-6.5 ./native --locales ko en ja
"""
import argparse
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('udid')
parser.add_argument('device_directory')
parser.add_argument('output_root', type=Path)
parser.add_argument('--locales', nargs='+', choices=['ko', 'en', 'ja'], default=['ko', 'en', 'ja'])
parser.add_argument('--fixtures', nargs='+', default=['learning-result', 'follow-up', 'feed', 'study-tree', 'statistics', 'custom-question'])
args = parser.parse_args()

def simctl(*values, **kwargs):
    subprocess.run(['xcrun', 'simctl', *values], check=True, **kwargs)

simctl('status_bar', args.udid, 'override', '--time', '9:41', '--dataNetwork', 'wifi', '--wifiMode', 'active', '--wifiBars', '3', '--batteryState', 'charged', '--batteryLevel', '100')
simctl('ui', args.udid, 'appearance', 'light')
for locale in args.locales:
    directory = args.output_root / args.device_directory / ('en-US' if locale == 'en' else locale)
    directory.mkdir(parents=True, exist_ok=True)
    for fixture in args.fixtures:
        env = os.environ.copy()
        env['SIMCTL_CHILD_BUDDYSTUDY_SCREENSHOT_FIXTURE'] = fixture
        env['SIMCTL_CHILD_BUDDYSTUDY_SCREENSHOT_LANGUAGE'] = locale
        simctl('launch', '--terminate-running-process', args.udid, 'io.github.ghkdqhrbals.StudyMate', env=env, stdout=subprocess.DEVNULL)
        time.sleep(4)  # Existing fixture workflow's bounded SwiftUI layout settle.
        target = directory / (fixture + '.png')
        simctl('io', args.udid, 'screenshot', str(target), stdout=subprocess.DEVNULL)
        print(target, flush=True)
