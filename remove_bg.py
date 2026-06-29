from PIL import Image

img = Image.open(
    r'C:\Users\ADMIN\.gemini\antigravity\brain\c4940c4d-7421-4377-a91a-8f2a590131e1\fleet_banner_final_1782467705683.png'
).convert('RGBA')

pixels = img.load()
w, h = img.size

for y in range(h):
    for x in range(w):
        r, g, b, a = pixels[x, y]
        # Remove white and near-white background (checkerboard area)
        # Also remove the light grey checkerboard squares
        if r > 200 and g > 200 and b > 200:
            pixels[x, y] = (r, g, b, 0)  # transparent
        # Also remove the grey checkerboard squares (around 180-210 grey)
        elif r > 175 and g > 175 and b > 175 and abs(int(r)-int(g)) < 15 and abs(int(g)-int(b)) < 15:
            pixels[x, y] = (r, g, b, 0)

out = r'C:\Users\ADMIN\.gemini\antigravity\scratch\Mconnect\app\src\main\res\drawable-nodpi\fleet_banner_composite.png'
img.save(out, 'PNG')
print(f'Done: {w}x{h}')
