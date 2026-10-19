#!/usr/bin/env python3
"""Convert the lint-flagged quantity strings to <plurals> entries."""
import re
import pathlib

p = pathlib.Path('app/src/main/res/values/strings.xml')
s = p.read_text()

plurals = {
    "reply_count": {
        "one": "View %1$d reply",
        "other": "View %1$d replies",
    },
    "me_age_years": {
        "one": "%1$d yr",
        "other": "%1$d yrs",
    },
    "search_result_user_subtitle": {
        "one": "ID: %1$d • %2$d follower",
        "other": "ID: %1$d • %2$d followers",
    },
    "search_result_user_subtitle_bio": {
        "one": "ID: %1$d • %2$d follower • %3$s",
        "other": "ID: %1$d • %2$d followers • %3$s",
    },
    "search_result_room_subtitle": {
        "one": "Host: %1$s • 👥 %2$d watching • %3$s",
        "other": "Host: %1$s • 👥 %2$d watching • %3$s",
    },
    "search_result_topic_subtitle": {
        "one": "%1$d moment posted under this topic",
        "other": "%1$d moments posted under this topic",
    },
    "coin_balance_fmt": {
        "one": "🪙 %1$,d Coin",
        "other": "🪙 %1$,d Coins",
    },
    "family_details_line": {
        "one": "Lv.%1$d • %2$d Member • Leader: %3$s",
        "other": "Lv.%1$d • %2$d Members • Leader: %3$s",
    },
    "family_my_status_member": {
        "one": "first",  # placeholder replaced below
        "other": "other_placeholder",
    },
    "redpacket_splits_remaining": {
        "one": "%1$d of %2$d splits remaining • Tap Open to claim!",
        "other": "%1$d of %2$d splits remaining • Tap Open to claim!",
    },
    "redpacket_claim_outcome": {
        "one": "🎉 You grabbed +%1$,d Coins! (%2$d split left)",
        "other": "🎉 You grabbed +%1$,d Coins! (%2$d splits left)",
    },
    "redpacket_received": {
        "one": "🎉 Received %1$,d Coin!",
        "other": "🎉 Received %1$,d Coins!",
    },
    "tasks_claimed": {
        "one": "🎉 Claimed +%1$,d Coin!",
        "other": "🎉 Claimed +%1$,d Coins!",
    },
    "task_reward_progress": {
        "one": "+%1$,d Coins (%2$d/%3$d)",
        "other": "+%1$,d Coins (%2$d/%3$d)",
    },
    "wheel_coins_balance_fmt": {
        "one": "🪙 %1$,d Coin",
        "other": "🪙 %1$,d Coins",
    },
}
# family_my_status_member keeps its escaped newline
plurals["family_my_status_member"] = {
    "one": r"%1$s %2$s\nRole: %3$s • Lv.%4$d • %5$d Member",
    "other": r"%1$s %2$s\nRole: %3$s • Lv.%4$d • %5$d Members",
}

for name, items in plurals.items():
    pat = re.compile(r'[ \t]*<string name="%s"[^>]*>.*?</string>\n' % re.escape(name), re.S)
    block = '    <plurals name="%s">\n' % name
    for q in ("one", "other"):
        block += '        <item quantity="%s">%s</item>\n' % (q, items[q])
    block += '    </plurals>\n'
    s, k = pat.subn(lambda m: block, s, count=1)
    if k == 0:
        print("MISS:", name)

p.write_text(s)
print("done")
