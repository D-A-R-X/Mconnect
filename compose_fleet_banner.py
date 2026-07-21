"""Compose the fleet van + phone cluster.

Match image 2: BIG van on the LEFT (front), phone to the right of the van
peeking up behind its rear, soft blue ripple beneath. Critically, a wide
transparent RIGHT padding is baked into the canvas so the XML stat cards
(which overlay the right edge of the banner) land on empty space and do NOT
cover the phone."""
from PIL import Image, ImageDraw, ImageFilter

BASE = "app/src/main/res/drawable-nodpi"
van = Image.open(f"{BASE}/fleet_banner_van.png").convert("RGBA")
phone = Image.open(f"{BASE}/fleet_banner_phone.png").convert("RGBA")

# Upscale for crisp output.
SCALE_VAN = 4.2
SCALE_PHONE = 3.0
van = van.resize((int(van.width * SCALE_VAN), int(van.height * SCALE_VAN)), Image.LANCZOS)
phone = phone.resize((int(phone.width * SCALE_PHONE), int(phone.height * SCALE_PHONE)), Image.LANCZOS)

# Layout tunables.
OVERLAP = int(van.width * 0.22)       # phone slips this far behind the van's rear
PHONE_RISE = int(van.height * 0.40)   # phone top sits this far above van top
LEFT_PAD = 24
TOP_PAD = 14
BOTTOM_PAD = 12
# Right padding sized to clear the area the stat cards will overlay. Sized
# generously so cards never cover the phone — they sit on this empty band.
# Cluster is now positioned over the cards (in front, via XML elevation), so
# no transparent right band is needed — keep just a tiny breathing margin.
RIGHT_PAD_CARDS = 30

content_w = van.width + phone.width - OVERLAP
W = LEFT_PAD + content_w + RIGHT_PAD_CARDS
H = max(van.height, phone.height + PHONE_RISE) + TOP_PAD + BOTTOM_PAD

canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))

van_x = LEFT_PAD
van_y = H - van.height - BOTTOM_PAD

# Soft blue ripple under the van.
ripple = Image.new("RGBA", (W, H), (0, 0, 0, 0))
rd = ImageDraw.Draw(ripple)
cx = van_x + van.width // 2
cy = van_y + van.height - int(van.height * 0.08)
rw, rh = int(van.width * 1.06), int(van.height * 0.42)
rd.ellipse([cx - rw // 2, cy - rh // 2, cx + rw // 2, cy + rh // 2], fill=(60, 150, 240, 140))
ripple = ripple.filter(ImageFilter.GaussianBlur(26))
canvas = Image.alpha_composite(canvas, ripple)

# Phone BEHIND the van, to the right, peeking up above the van's roof.
phone_x = van_x + van.width - OVERLAP
phone_y = van_y - PHONE_RISE
canvas.alpha_composite(phone, (phone_x, max(0, phone_y)))

# Van IN FRONT.
canvas.alpha_composite(van, (van_x, van_y))

out = f"{BASE}/fleet_van_phone_cluster.png"
canvas.save(out)
print("saved", out, canvas.size, "(content_w =", content_w, ", right_pad =", RIGHT_PAD_CARDS, ")")
