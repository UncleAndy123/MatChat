#!/usr/bin/env python3
"""Generate the MatChat mockup artboards (.dc.html) at true device size 240x320."""
import os, json, pathlib

OUT = pathlib.Path(__file__).parent

PAPER = "#F6F4EE"; INK = "#14130E"; MUTED = "#55524A"; RULE = "#C9C4B8"
ACCENT = "#A15C00"; ALERT = "#8A1E15"; GREEN = "#1F5C4A"

CSS = f"""
    @import url('https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700&display=swap');
    * {{ box-sizing: border-box; }}
    body {{ margin: 0; width: 240px; height: 320px; background: {PAPER}; color: {INK};
      font-family: 'Atkinson Hyperlegible', 'Segoe UI', system-ui, sans-serif;
      -webkit-font-smoothing: antialiased; overflow: hidden; }}
    a {{ color: {ACCENT}; }} a:hover {{ color: #7d4600; }}
    .screen {{ width: 240px; height: 320px; display: flex; flex-direction: column;
      background: {PAPER}; }}
    .title {{ flex: 0 0 18px; display: flex; align-items: center; justify-content: space-between;
      gap: 4px; padding: 0 6px; font-size: 12px; font-weight: 700; line-height: 18px;
      border-bottom: 1px solid {RULE}; }}
    .title .t {{ overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }}
    .title .g {{ flex: 0 0 auto; display: flex; align-items: center; gap: 3px; }}
    .body {{ flex: 1 1 auto; display: flex; flex-direction: column; overflow: hidden; }}
    .pad {{ padding: 8px; }}
    .keys {{ flex: 0 0 20px; display: flex; align-items: center; background: {INK};
      color: {PAPER}; font-size: 11px; font-weight: 700; line-height: 20px; padding: 0 6px; }}
    .keys .l {{ flex: 0 0 auto; }} .keys .c {{ flex: 1 1 auto; text-align: center; }}
    .keys .r {{ flex: 0 0 auto; }}
    .row {{ display: flex; flex-direction: column; justify-content: center; gap: 1px;
      height: 44px; padding: 0 6px; border-bottom: 1px solid {RULE}; }}
    .row .a {{ display: flex; align-items: baseline; gap: 6px; }}
    .name {{ font-size: 16px; font-weight: 700; overflow: hidden; white-space: nowrap;
      text-overflow: ellipsis; flex: 1 1 auto; }}
    .time {{ font-size: 11px; color: {MUTED}; flex: 0 0 auto; }}
    .prev {{ font-size: 13px; color: {MUTED}; overflow: hidden; white-space: nowrap;
      text-overflow: ellipsis; flex: 1 1 auto; }}
    .badge {{ flex: 0 0 auto; min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px;
      background: {INK}; color: {PAPER}; font-size: 11px; font-weight: 700; line-height: 16px;
      text-align: center; }}
    .focus {{ background: {INK}; color: {PAPER}; }}
    .focus .time, .focus .prev {{ color: #CFCBBF; }}
    .focus .badge {{ background: {PAPER}; color: {INK}; }}
    .btn {{ display: flex; align-items: center; height: 30px; padding: 0 8px; font-size: 15px;
      font-weight: 700; border: 2px solid {INK}; background: {PAPER}; color: {INK}; }}
    .btn.focus {{ background: {INK}; color: {PAPER}; }}
    .field {{ display: flex; flex-direction: column; gap: 1px; }}
    .field .lab {{ font-size: 11px; color: {MUTED}; font-weight: 700;
      text-transform: uppercase; letter-spacing: .04em; }}
    .field .box {{ height: 26px; border: 2px solid {RULE}; background: #FFFDF7; padding: 0 6px;
      font-size: 16px; line-height: 22px; color: {INK}; }}
    .field .box.focus {{ border-color: {INK}; }}
    .h1 {{ font-size: 17px; font-weight: 700; line-height: 20px; }}
    .p {{ font-size: 13px; line-height: 17px; color: {MUTED}; }}
    .sep {{ display: flex; align-items: center; gap: 6px; padding: 3px 6px; }}
    .sep i {{ flex: 1 1 auto; height: 1px; background: {RULE}; }}
    .sep span {{ font-size: 11px; color: {MUTED}; letter-spacing: .06em; text-transform: uppercase; }}
    .msg {{ padding: 3px 6px; }}
    .who {{ font-size: 12px; font-weight: 700; color: {GREEN}; }}
    .txt {{ font-size: 16px; line-height: 19px; }}
    .meta {{ font-size: 11px; color: {MUTED}; text-align: right; }}
    .own {{ text-align: right; }}
    .own .txt {{ display: inline-block; text-align: left; }}
    .input {{ flex: 0 0 auto; border-top: 1px solid {RULE}; padding: 2px 6px; font-size: 14px;
      color: {MUTED}; background: #FFFDF7; }}
    .banner {{ flex: 0 0 auto; background: {ALERT}; color: {PAPER}; font-size: 11px;
      font-weight: 700; padding: 3px 6px; }}
    .menu {{ position: absolute; left: 0; right: 0; bottom: 20px; background: {PAPER};
      border-top: 2px solid {INK}; }}
    .menu div {{ height: 26px; display: flex; align-items: center; padding: 0 8px;
      font-size: 15px; border-bottom: 1px solid {RULE}; }}
    .scrim {{ position: absolute; inset: 18px 0 20px 0; background: rgba(20,19,14,.45); }}
"""

LOCK = ('<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        'stroke-width="2.5"><rect x="4" y="10" width="16" height="11" rx="2"/>'
        '<path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>')
SYNC = ('<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        'stroke-width="2.5"><path d="M20 12a8 8 0 1 1-2.3-5.6"/><path d="M20 3v5h-5"/></svg>')
WARN = ('<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        'stroke-width="2.5"><path d="M12 3 22 20H2z"/><path d="M12 10v4"/><path d="M12 17.5v.5"/></svg>')


def page(title_bar, body, keys, extra_body_style=""):
    return f"""<div class="screen" style="position: relative;{extra_body_style}">
{title_bar}
{body}
{keys}
</div>"""


def titlebar(text, glyph=SYNC):
    return f'<div class="title"><span class="t">{text}</span><span class="g">{glyph}</span></div>'


def keybar(l, c, r):
    return (f'<div class="keys"><span class="l">{l}</span>'
            f'<span class="c">{c}</span><span class="r">{r}</span></div>')


def room_row(name, prev, time, unread=0, focus=False):
    badge = f'<span class="badge">{unread}</span>' if unread else ''
    f = " focus" if focus else ""
    return (f'<div class="row{f}"><div class="a"><span class="name">{name}</span>'
            f'<span class="time">{time}</span></div>'
            f'<div class="a"><span class="prev">{prev}</span>{badge}</div></div>')


ROOMS = [
    ("Barn Crew", "Wayne: I'll be there at six", "3:42p", 3, True),
    ("Deacons", "Elmer: Meeting moved to Tuesday", "1:05p", 1, False),
    ("Fire Company", "Ray: Truck is back from service", "Wed", 0, False),
    ("Family", "Mother: Supper at five", "Tue", 0, False),
    ("Shop Floor", "Merv: The parts came in", "Mon", 0, False),
    ("Church Notices", "Bulletin for Sunday", "Sun", 0, False),
]

screens = {}

# ---------- S8 room list (entry) ----------
INVITE_BAND = (f'<div style="flex: 0 0 18px; display: flex; align-items: center; '
               f'justify-content: space-between; padding: 0 5px; margin: 0 1px; '
               f'border: 2px solid {INK}; font-size: 12px; font-weight: 700; '
               f'line-height: 14px;"><span>Invitation</span>'
               f'<span class="badge">1</span></div>')
screens["Main"] = page(
    titlebar("MatChat"),
    '<div class="body">' + INVITE_BAND
    + "".join(room_row(*r) for r in ROOMS[:6]) + '</div>',
    keybar("Options", "Open", "Exit"))

# ---------- S9 timeline ----------
timeline_body = f"""<div class="body">
  <div class="sep"><i></i><span>Yesterday</span><i></i></div>
  <div class="msg"><div class="who">Wayne Z.</div>
    <div class="txt">Are we still meeting at the barn at six?</div>
    <div class="meta">3:30p</div></div>
  <div class="msg own"><div class="txt">Yes &mdash; I'll bring the trailer.</div>
    <div class="meta">3:34p &#10003;</div></div>
  <div class="msg focus" style="border-left: 3px solid {INK};">
    <div class="who" style="color: #9ED2BF;">Ray M.</div>
    <div class="txt">Bring the long chains too, we will need them.</div>
    <div class="meta" style="color: #CFCBBF;">3:40p</div></div>
  <div class="msg" style="background: #EDE9DE;">
    <div class="txt" style="font-size: 13px; font-style: italic; color: {MUTED};">
      This message can't be read on this phone yet.</div>
    <div style="font-size: 13px; font-weight: 700; text-decoration: underline;">Fix encryption</div>
  </div>
  <div style="flex: 1 1 auto;"></div>
  <div class="input">Write a message</div>
</div>"""
screens["Timeline"] = page(titlebar("Barn Crew"), timeline_body,
                           keybar("Options", "Select", "Back"))

# ---------- S10 compose ----------
compose_body = f"""<div class="body">
  <div class="msg"><div class="who">Wayne Z.</div>
    <div class="txt">Are we still meeting at the barn at six?</div>
    <div class="meta">3:30p</div></div>
  <div class="msg own"><div class="txt">Yes &mdash; I'll bring the trailer.</div>
    <div class="meta">3:34p &#10003;</div></div>
  <div class="msg"><div class="who">Ray M.</div>
    <div class="txt">Bring the long chains too.</div>
    <div class="meta">3:40p</div></div>
  <div style="flex: 1 1 auto;"></div>
  <div style="flex: 0 0 auto; border-top: 2px solid {INK}; background: #FFFDF7; padding: 3px 6px;">
    <div style="font-size: 16px; line-height: 19px;">Chains are in the shop, I will
      load them in the morning<span style="border-left: 2px solid {INK}; margin-left: 1px;">&nbsp;</span></div>
    <div style="font-size: 11px; color: {MUTED}; text-align: right;">Multi-tap &middot; abc</div>
  </div>
</div>"""
screens["Compose"] = page(titlebar("Barn Crew"), compose_body,
                          keybar("Options", "Send", "Back"))

# ---------- S11 message menu ----------
menu_body = f"""<div class="body" style="position: relative;">
  <div class="msg"><div class="who">Wayne Z.</div>
    <div class="txt">Are we still meeting at the barn at six?</div>
    <div class="meta">3:30p</div></div>
  <div class="msg own"><div class="txt">Yes &mdash; I'll bring the trailer.</div>
    <div class="meta">3:34p &#10003;</div></div>
  <div class="msg"><div class="who">Ray M.</div>
    <div class="txt">Bring the long chains too.</div>
    <div class="meta">3:40p</div></div>
</div>
<div class="scrim"></div>
<div class="menu">
  <div class="focus">Reply</div>
  <div>Copy text</div>
  <div>Message info</div>
  <div style="border-bottom: none;">Delete</div>
</div>"""
screens["MessageMenu"] = page(titlebar("Barn Crew"), menu_body,
                              keybar("", "Select", "Back"))

# ---------- S1 splash ----------
splash_body = f"""<div class="body" style="align-items: center; justify-content: center; gap: 10px;">
  <div style="font-size: 24px; font-weight: 700; letter-spacing: -.01em;">MatChat</div>
  <div style="width: 120px; height: 3px; background: {RULE};">
    <div style="width: 46px; height: 3px; background: {INK};"></div></div>
  <div class="p" style="font-size: 14px;">Signing you in&hellip;</div>
</div>"""
screens["Splash"] = page(titlebar("MatChat"), splash_body, keybar("", "", "Cancel"))

# ---------- S2 welcome ----------
welcome_body = f"""<div class="body pad" style="gap: 10px;">
  <div class="h1">MatChat</div>
  <div class="p">Group messages for this phone. Your groups are set up by your
    administrator.</div>
  <div style="display: flex; flex-direction: column; gap: 6px; margin-top: 2px;">
    <div class="btn focus">Sign in with QR code</div>
    <div class="btn">Sign in with password</div>
    <div class="btn" style="border-color: {RULE};">Help</div>
  </div>
</div>"""
screens["Welcome"] = page(titlebar("Welcome"), welcome_body,
                          keybar("Options", "Select", "Exit"))

# ---------- S3 sign in ----------
signin_body = f"""<div class="body pad" style="gap: 8px;">
  <div style="display: flex; align-items: center; gap: 4px; font-size: 12px; color: {MUTED};
    border: 1px solid {RULE}; background: #EDE9DE; padding: 3px 6px;">
    {LOCK}<span>chats.carpathianserver.org</span></div>
  <div class="field"><span class="lab">Username</span>
    <div class="box focus">wonkySim</div></div>
  <div class="field"><span class="lab">Password</span>
    <div class="box">***********</div></div>
  <div class="btn" style="margin-top: 2px;">Sign in</div>
</div>"""
screens["SignIn"] = page(titlebar("Sign in"), signin_body,
                         keybar("Options", "Select", "Back"))

# ---------- S4 QR sign in ----------
corner = ("position: absolute; width: 22px; height: 22px; border: 3px solid " + INK + ";")
qr_body = f"""<div class="body" style="align-items: center; justify-content: center; gap: 8px;">
  <div style="position: relative; width: 200px; height: 200px; background: #E4E0D4;">
    <div style="{corner} top: 0; left: 0; border-right: none; border-bottom: none;"></div>
    <div style="{corner} top: 0; right: 0; border-left: none; border-bottom: none;"></div>
    <div style="{corner} bottom: 0; left: 0; border-right: none; border-top: none;"></div>
    <div style="{corner} bottom: 0; right: 0; border-left: none; border-top: none;"></div>
    <div style="position: absolute; inset: 0; display: flex; align-items: center;
      justify-content: center; font-size: 11px; color: {MUTED};">Camera</div>
  </div>
  <div class="p" style="text-align: center; padding: 0 10px;">On your other device:<br>
    Settings &rarr; Link a device</div>
</div>"""
screens["SignInQr"] = page(titlebar("Scan code"), qr_body, keybar("", "", "Back"))

# ---------- S5 encryption setup ----------
enc_body = f"""<div class="body pad" style="gap: 8px;">
  <div class="h1">Protect your messages</div>
  <div class="p">Only you and the people in your groups can read what is sent.
    Set this phone up once.</div>
  <div style="display: flex; flex-direction: column; gap: 6px;">
    <div class="btn focus">Verify with another device</div>
    <div class="btn">Enter recovery key</div>
    <div class="btn" style="border-color: {RULE};">Skip for now</div>
  </div>
</div>"""
screens["Encryption"] = page(titlebar("Encryption"), enc_body,
                             keybar("Options", "Select", "Back"))

# ---------- S6 emoji verification ----------
EMOJI = [("\U0001F436", "Dog"), ("\U0001F431", "Cat"), ("\U0001F981", "Lion"),
         ("\U0001F40E", "Horse"), ("\U0001F984", "Unicorn"), ("\U0001F437", "Pig"),
         ("\U0001F418", "Elephant")]
def cell(e, w):
    return (f'<div style="width: 52px; text-align: center;">'
            f'<div style="font-size: 22px; line-height: 24px;">{e}</div>'
            f'<div style="font-size: 11px; color: {MUTED};">{w}</div></div>')
grid = ('<div style="display: flex; justify-content: center; gap: 2px;">'
        + "".join(cell(*x) for x in EMOJI[:4]) + '</div>'
        '<div style="display: flex; justify-content: center; gap: 2px;">'
        + "".join(cell(*x) for x in EMOJI[4:]) + '</div>')
verify_body = f"""<div class="body pad" style="gap: 6px;">
  <div class="p" style="color: {INK}; font-size: 14px;">Do these appear on your other device?</div>
  {grid}
  <div style="display: flex; flex-direction: column; gap: 5px; margin-top: 2px;">
    <div class="btn focus">They match</div>
    <div class="btn" style="border-color: {ALERT}; color: {ALERT};">They do not match</div>
  </div>
</div>"""
screens["Verify"] = page(titlebar("Verify device"), verify_body,
                         keybar("Options", "Select", "Cancel"))

# ---------- S7 recovery key ----------
rec_body = f"""<div class="body pad" style="gap: 8px;">
  <div class="h1">Recovery key</div>
  <div class="p">Type the key your administrator gave you. Groups of four.</div>
  <div class="field">
    <div class="box focus" style="height: 30px; letter-spacing: .08em; font-size: 15px;
      line-height: 26px;">EsTv 8Kq2 7Hxn <span style="color: {MUTED};">____</span></div>
    <div style="display: flex; justify-content: space-between; font-size: 11px; color: {MUTED};">
      <span>12 / 48</span><span>Options: verify with a device</span></div>
  </div>
  <div class="btn">Continue</div>
</div>"""
screens["RecoveryKey"] = page(titlebar("Recovery key"), rec_body,
                              keybar("Options", "Select", "Back"))

# ---------- S12 room info ----------
def member(name, role="", focus=False):
    f = " focus" if focus else ""
    r = f'<span class="time">{role}</span>' if role else ''
    return (f'<div class="row{f}" style="height: 30px;"><div class="a">'
            f'<span class="name" style="font-size: 15px;">{name}</span>{r}</div></div>')
info_body = f"""<div class="body">
  <div class="pad" style="padding: 6px 8px; border-bottom: 1px solid {RULE};">
    <div class="h1">Barn Crew</div>
    <div style="display: flex; align-items: center; gap: 4px; font-size: 11px; color: {GREEN};
      font-weight: 700;">{LOCK}<span>Encrypted &middot; 6 members</span></div>
  </div>
  {member("Wayne Z.", "Admin", True)}
  {member("Ray M.")}
  {member("Merv S.")}
  {member("Elmer K.")}
  {member("Andrew B.", "You")}
</div>"""
screens["RoomInfo"] = page(titlebar("Group info"), info_body,
                           keybar("Options", "Select", "Back"))

# ---------- S13 settings ----------
def srow(label, value="", focus=False):
    f = " focus" if focus else ""
    v = f'<span class="time">{value}</span>' if value else ''
    return (f'<div class="row{f}" style="height: 32px;"><div class="a">'
            f'<span class="name" style="font-size: 15px;">{label}</span>{v}</div></div>')
set_body = ('<div class="body">'
            + srow("Notifications", "On", True)
            + srow("Text size", "Normal")
            + srow("Encryption", "Verified")
            + srow("This phone's session")
            + srow("Policy", "Managed")
            + srow("Help")
            + srow("Sign out")
            + '</div>')
screens["Settings"] = page(titlebar("Settings"), set_body,
                           keybar("Options", "Select", "Back"))

# ---------- S14 help ----------
def hrow(k, v, focus=False):
    f = " focus" if focus else ""
    return (f'<div class="row{f}" style="height: 34px; gap: 0;">'
            f'<div style="font-size: 14px; font-weight: 700;">{k}</div>'
            f'<div style="font-size: 12px; color: {"#CFCBBF" if focus else MUTED};">{v}</div></div>')
help_body = ('<div class="body">'
             + hrow("Up / Down", "Move the highlight", True)
             + hrow("Middle key", "Open what is highlighted")
             + hrow("Left key", "Options for this screen")
             + hrow("Right key", "Go back")
             + hrow("Hold #", "Jump to the next unread group")
             + hrow("Hold *", "Make the text bigger")
             + '</div>')
screens["Help"] = page(titlebar("Help"), help_body, keybar("", "Select", "Back"))

# ---------- S15 notification ----------
notif_body = f"""<div class="body" style="background: #23211A; padding: 8px; gap: 8px;">
  <div style="background: {PAPER}; border-left: 4px solid {INK}; padding: 5px 6px;">
    <div style="font-size: 11px; font-weight: 700; color: {MUTED};">MATCHAT</div>
    <div style="font-size: 16px; font-weight: 700;">Barn Crew</div>
    <div style="font-size: 13px; color: {MUTED};">3 new messages</div>
  </div>
  <div style="background: #35322A; color: #CFCBBF; padding: 5px 6px; font-size: 12px;">
    <div style="font-weight: 700;">MatChat is running</div>
    <div style="font-size: 11px;">Keeping your groups up to date</div>
  </div>
  <div style="flex: 1 1 auto;"></div>
  <div style="color: #A9A498; font-size: 11px; text-align: center;">
    Notification shade &mdash; select to open the group</div>
</div>"""
screens["Notification"] = page(titlebar("System notifications", ""), notif_body,
                               keybar("", "Open", "Back"))

# ---------- empty + offline state ----------
empty_body = f"""<div class="body">
  <div class="banner">No connection &mdash; showing saved messages</div>
  <div style="flex: 1 1 auto; display: flex; flex-direction: column; align-items: center;
    justify-content: center; gap: 8px; padding: 0 16px; text-align: center;">
    <div style="width: 34px; height: 34px; border: 3px solid {RULE};"></div>
    <div style="font-size: 15px; font-weight: 700;">No groups yet</div>
    <div class="p">Your groups will appear here. Ask your administrator to add you.</div>
  </div>
</div>"""
screens["Empty"] = page(titlebar("MatChat", WARN), empty_body,
                        keybar("Options", "", "Exit"))

# ---------- large text alternate ----------
def big_row(name, prev, time, unread=0, focus=False):
    badge = f'<span class="badge" style="height: 20px; line-height: 20px; font-size: 14px; min-width: 20px; border-radius: 10px;">{unread}</span>' if unread else ''
    f = " focus" if focus else ""
    return (f'<div class="row{f}" style="height: 64px;"><div class="a">'
            f'<span class="name" style="font-size: 21px;">{name}</span></div>'
            f'<div class="a"><span class="prev" style="font-size: 16px;">{prev}</span>'
            f'{badge}</div></div>')
big_body = ('<div class="body">'
            + big_row("Barn Crew", "Wayne: I'll be there", "3:42p", 3, True)
            + big_row("Deacons", "Elmer: Moved to Tuesday", "1:05p", 1)
            + big_row("Fire Company", "Ray: Truck is back", "Wed")
            + big_row("Family", "Mother: Supper at five", "Tue")
            + '</div>')
screens["RoomListLarge"] = page(titlebar("MatChat"), big_body,
                                keybar("Options", "Open", "Exit"))

# ---------- S18 invitations ----------
def invite_row(name, frm, focus=False, blocked=False):
    f = " focus" if focus else ""
    tag = (f'<span class="time" style="color: {"#F0B5AE" if focus else ALERT}; '
           f'font-weight: 700;">Not allowed</span>') if blocked else ''
    return (f'<div class="row{f}" style="height: 36px;">'
            f'<div class="a"><span class="name">{name}</span>{tag}</div>'
            f'<div class="a"><span class="prev" style="font-size: 11px;">from {frm}</span></div>'
            f'</div>')

invites_body = ('<div class="body">'
                + invite_row("Wayne Zimmerman", "@wayne:example.org", focus=True)
                + invite_row("Volunteer Drivers", "@ray:carpathianserver.org")
                + invite_row("Ed Tanner", "@ed:otherplace.net", blocked=True)
                + '</div>')
screens["Invites"] = page(titlebar("Invitations"), invites_body,
                          keybar("", "Open", "Back"))

# ---------- S19 invitation detail ----------
def invite_detail(title, lines, actions, note=None):
    body = f'<div class="body pad" style="gap: 6px;"><div class="h1">{title}</div>'
    for lab, val in lines:
        body += (f'<div><div style="font-size: 11px; color: {MUTED}; font-weight: 700;">{lab}</div>'
                 f'<div style="font-size: 14px; word-break: break-all;">{val}</div></div>')
    if note:
        body += (f'<div style="font-size: 12px; line-height: 15px; color: {ALERT}; '
                 f'font-weight: 700;">{note}</div>')
    body += '<div style="flex: 1 1 auto;"></div>'
    body += '<div style="display: flex; flex-direction: column; gap: 5px;">' + actions + '</div>'
    return body + '</div>'

screens["InviteDetail"] = page(
    titlebar("Invitation"),
    invite_detail("Wayne Zimmerman",
                  [("INVITED BY", "@wayne:example.org"),
                   ("SERVER", "example.org"),
                   ("TYPE", "Direct message &middot; encrypted")],
                  '<div class="btn focus">Accept</div><div class="btn">Decline</div>'),
    keybar("Options", "Select", "Back"))

screens["InviteBlocked"] = page(
    titlebar("Invitation"),
    invite_detail("Ed Tanner",
                  [("INVITED BY", "@ed:otherplace.net"),
                   ("SERVER", "otherplace.net")],
                  '<div class="btn focus">Decline</div>',
                  note="Your organization does not allow messages from otherplace.net."),
    keybar("Options", "Select", "Back"))

# ---------- S20 new message ----------
def header(text):
    return (f'<div style="padding: 3px 6px 1px; font-size: 11px; font-weight: 700; '
            f'color: {MUTED}; letter-spacing: .06em; text-transform: uppercase;">{text}</div>')

def two_line(top, bottom, focus=False):
    f = " focus" if focus else ""
    return (f'<div class="row{f}" style="height: 32px;">'
            f'<div class="a"><span class="name" style="font-size: 15px;">{top}</span></div>'
            f'<div class="a"><span class="prev" style="font-size: 11px;">{bottom}</span></div></div>')

newchat_body = ('<div class="body">'
                + header("Contacts")
                + two_line("Wayne Zimmerman", "@wayne:example.org", focus=True)
                + two_line("Merv Stoltzfus", "@merv:carpathianserver.org")
                + header("Recent")
                + two_line("@ray:carpathianserver.org", "3 days ago")
                + header("")
                + '<div class="row" style="height: 30px;">'
                  '<div class="a"><span class="name" style="font-size: 15px; '
                  'text-decoration: underline;">Type an address</span></div></div>'
                + '</div>')
screens["NewChat"] = page(titlebar("New message"), newchat_body,
                          keybar("", "Select", "Back"))

# ---------- S21 type an address ----------
addr_body = f"""<div class="body pad" style="gap: 8px;">
  <div class="field"><span class="lab">Address</span>
    <div class="box focus" style="font-size: 15px;">@wayne<span style="color: {MUTED};">:</span>example.org<span style="border-left: 2px solid {INK}; margin-left: 1px;">&nbsp;</span></div>
    <div style="font-size: 11px; color: {MUTED};">Example: @wayne:example.org</div>
  </div>
  <div class="btn">Continue</div>
  <div style="flex: 1 1 auto;"></div>
  <div style="font-size: 11px; color: {MUTED}; line-height: 14px;">
    Options &rarr; Use my server fills in carpathianserver.org</div>
</div>"""
screens["TypeAddress"] = page(titlebar("Type an address"), addr_body,
                              keybar("Options", "Select", "Back"))

# ---------- S21 confirmation ----------
confirm_body = f"""<div class="body pad" style="gap: 8px;">
  <div class="p" style="font-size: 14px; color: {INK};">Send to</div>
  <div class="h1">Wayne Zimmerman</div>
  <div style="font-size: 13px; color: {MUTED}; word-break: break-all;">@wayne:example.org</div>
  <div style="display: flex; align-items: center; gap: 4px; font-size: 11px; color: {GREEN};
    font-weight: 700;">{LOCK}<span>New chat will be encrypted</span></div>
  <div style="flex: 1 1 auto;"></div>
  <div style="display: flex; flex-direction: column; gap: 5px;">
    <div class="btn focus">Start chat</div>
    <div class="btn" style="border-color: {RULE};">Change</div>
  </div>
</div>"""
screens["AddressConfirm"] = page(titlebar("New message"), confirm_body,
                                 keybar("", "Select", "Back"))

# ---------- S22 address not allowed ----------
blocked_body = f"""<div class="body pad" style="gap: 8px; align-items: center;
  justify-content: center; text-align: center;">
  <div style="width: 30px; height: 30px; border: 3px solid {ALERT}; color: {ALERT};
    display: flex; align-items: center; justify-content: center; font-size: 20px;
    font-weight: 700;">!</div>
  <div class="h1" style="word-break: break-all;">otherplace.net</div>
  <div class="p" style="font-size: 13px;">Your organization does not allow
    messages to this server.</div>
  <div style="font-size: 11px; color: {MUTED};">Managed by your organization</div>
</div>"""
screens["AddressBlocked"] = page(titlebar("Not allowed", WARN), blocked_body,
                                 keybar("", "", "Back"))

# ---------- Settings > Policy ----------
policy_body = f"""<div class="body">
  <div style="padding: 6px 8px; border-bottom: 1px solid {RULE};">
    <div style="font-size: 15px; font-weight: 700;">Managed by your organization</div>
    <div style="font-size: 11px; color: {MUTED};">Settings come from your administrator</div>
  </div>
  <div style="padding: 5px 8px; border-bottom: 1px solid {RULE};">
    <div style="font-size: 11px; color: {MUTED}; font-weight: 700;">HOME SERVER</div>
    <div style="font-size: 13px; word-break: break-all;">chats.carpathianserver.org</div>
  </div>
  <div style="padding: 5px 8px; border-bottom: 1px solid {RULE};">
    <div style="font-size: 11px; color: {MUTED}; font-weight: 700;">ALLOWED SERVERS</div>
    <div style="font-size: 13px; word-break: break-all;">carpathianserver.org<br>example.org</div>
  </div>
  <div style="padding: 5px 8px;">
    <div style="font-size: 11px; color: {MUTED}; font-weight: 700;">DIRECT MESSAGES</div>
    <div style="font-size: 13px;">Allowed</div>
  </div>
</div>"""
screens["PolicyInfo"] = page(titlebar("Policy"), policy_body,
                             keybar("", "", "Back"))

TEMPLATE = """<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <style>{css}</style>
</helmet>
{content}
</x-dc>
</body>
</html>
"""

for name, content in screens.items():
    (OUT / f"{name}.dc.html").write_text(
        TEMPLATE.format(css=CSS, content=content), encoding="utf-8")

# ---------- landscape check, 320x240 (DuraXE Epic class) ----------
LAND_CSS = CSS.replace("width: 240px; height: 320px", "width: 320px; height: 240px")
land_body = ('<div class="body">'
             + "".join(room_row(*r) for r in ROOMS[:4])
             + '<div style="flex: 1 1 auto;"></div></div>')
land = page(titlebar("MatChat"), land_body, keybar("Options", "Open", "Exit"))
land = land.replace('class="screen" style="position: relative;',
                    'class="screen" style="position: relative; width: 320px; height: 240px;')
(OUT / "RoomListLandscape.dc.html").write_text(
    TEMPLATE.format(css=LAND_CSS, content=land), encoding="utf-8")
screens["RoomListLandscape"] = land

# ---------- canvas.json ----------
COL, ROW = 340, 500
layout = [
    ["Main", "Timeline", "Compose", "MessageMenu"],
    ["RoomInfo", "Settings", "Help", "Notification"],
    ["Splash", "Welcome", "SignIn", "SignInQr"],
    ["Encryption", "Verify", "RecoveryKey", "Empty", "RoomListLarge"],
    ["Invites", "InviteDetail", "InviteBlocked", "NewChat"],
    ["TypeAddress", "AddressConfirm", "AddressBlocked", "PolicyInfo"],
]
artboards = []
for r, rowlist in enumerate(layout):
    for c, nm in enumerate(rowlist):
        artboards.append({"file": f"{nm}.dc.html", "x": c * COL, "y": r * ROW,
                          "w": 240, "h": 320})
artboards.append({"file": "RoomListLandscape.dc.html", "x": 5 * COL, "y": 3 * ROW,
                  "w": 320, "h": 240})
canvas = {
    "artboards": artboards,
    "annotations": [
        {"id": "brief", "x": 0, "y": -190, "w": 700,
         "text": "MatChat - Matrix client for D-pad feature phones.\n"
                 "Every artboard is one screen at true device size: 240x320 px, 2.6\" QVGA\n"
                 "(plus one 320x240 landscape check).\n"
                 "Focus is an inverted block. The bottom bar is Options | Select | Back -\n"
                 "LEFT is blank only where a screen has no options, and never means\n"
                 "anything else. Screens follow docs/UX-SPEC.md."},
        {"id": "row-daily", "x": -300, "y": 0, "w": 250,
         "text": "DAILY USE\nS8 room list, S9 timeline, S10 compose, S11 message menu."},
        {"id": "row-secondary", "x": -300, "y": ROW, "w": 250,
         "text": "AROUND THE EDGES\nS12 group info, S13 settings, S14 help (the only manual\n"
                 "a user has), S15 notifications."},
        {"id": "row-onboarding", "x": -300, "y": 2 * ROW, "w": 250,
         "text": "ONBOARDING\nS1-S4. Homeserver is pinned and not editable.\n"
                 "QR sign-in avoids typing a password on a keypad."},
        {"id": "row-crypto", "x": -300, "y": 3 * ROW, "w": 250,
         "text": "ENCRYPTION + EDGE STATES\nS5-S7, plus the empty/offline room list."},
        {"id": "alt-large", "x": 4 * COL, "y": 3 * ROW - 130, "w": 250,
         "text": "LARGE-TEXT MODE (hold *) - spec S16.\nSame rows, 21px names, four visible.\n"
                 "Row heights are min-heights, never fixed, so this is a scale\n"
                 "change and not a second layout."},
        {"id": "row-invites", "x": -300, "y": 4 * ROW, "w": 250,
         "text": "INVITATIONS (S18-S19)\nNothing auto-joins. An invite from a blocked domain is\n"
                 "still SHOWN, with the reason - a user who cannot see an\n"
                 "invitation cannot ask anyone about it."},
        {"id": "row-address", "x": -300, "y": 5 * ROW, "w": 250,
         "text": "DIRECT CHAT BY ADDRESS (S20-S22)\nContacts and recents, then typing. The @ and : are field\n"
                 "furniture, not keys to hunt for. Confirm the person before\n"
                 "creating the room - a typo otherwise leaves an orphan chat.\n"
                 "Last artboard: Settings > Policy, so a blocked user can find\n"
                 "out why without phoning anyone."},
        {"id": "alt-landscape", "x": 5 * COL, "y": 3 * ROW - 130, "w": 260,
         "text": "LANDSCAPE CHECK (spec S17) - 320x240, DuraXE Epic class.\n"
                 "Same screens, 202px content band: four rooms instead of six.\n"
                 "Every screen must survive this size."},
    ],
    "launch": {"view": "canvas"},
}
(OUT / "canvas.json").write_text(json.dumps(canvas, indent=2), encoding="utf-8")
print(f"wrote {len(screens)} artboards + canvas.json")
