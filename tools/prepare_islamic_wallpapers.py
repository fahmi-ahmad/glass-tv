import urllib.request
import json
import os
import io
from PIL import Image

HEADERS = {'User-Agent': 'GlassTVIslamicWallpapers/1.0 (antigravity@gemini.local)'}

ITEMS = [
    {
        'file': 'File:Masjid_al-Haram_2022.jpg',
        'title': 'Masjidil Haram',
        'desc': 'Masjid suci paling utama dalam Islam yang mengelilingi Kaabah, kiblat seluruh umat Islam.',
        'country': 'Makkah, Arab Saudi'
    },
    {
        'file': 'File:Al-Masjid_an-Nabawi.jpg',
        'title': 'Masjid Nabawi',
        'desc': 'Masjid suci kedua umat Islam yang dibina oleh Rasulullah SAW di Madinah Al-Munawwarah.',
        'country': 'Madinah, Arab Saudi'
    },
    {
        'file': 'File:16-04-04-Felsendom-Tempelberg-Jerusalem-RalfR-WAT_6385.jpg',
        'title': 'Kubah As-Sakhrah',
        'desc': 'Monumen bersejarah di kawasan Masjid Al-Aqsa, tempat berlakunya peristiwa agung Mikraj.',
        'country': 'Baitulmaqdis, Palestin'
    },
    {
        'file': 'File:Dataran_Putra_Square_dan_Masjid_Putra.jpg',
        'title': 'Masjid Putra',
        'desc': 'Masjid merah jambu yang megah menghadap Tasik Putrajaya berlatarkan seni bina Islam Parsi.',
        'country': 'Putrajaya, Malaysia'
    },
    {
        'file': 'File:Crystal_Mosque_Aerial_Shot_1.jpg',
        'title': 'Masjid Kristal',
        'desc': 'Masjid unik berstruktur kaca dan keluli di pesisir Sungai Terengganu di Taman Tamadun Islam.',
        'country': 'Kuala Terengganu, Malaysia'
    },
    {
        'file': 'File:Blue_Mosque_Istanbul.jpg',
        'title': 'Masjid Sultan Ahmed',
        'desc': 'Masjid Biru yang termasyhur dengan kubah bertingkat dan seni jubin seramik Iznik yang mempesona.',
        'country': 'Istanbul, Turki'
    }
]

def fetch_image_url(file_name):
    api_url = f"https://commons.wikimedia.org/w/api.php?action=query&titles={urllib.parse.quote(file_name)}&prop=imageinfo&iiprop=url|thumburl&iiurlwidth=1920&format=json"
    req = urllib.request.Request(api_url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        pages = data['query']['pages']
        for pid, pdata in pages.items():
            if 'imageinfo' in pdata and len(pdata['imageinfo']) > 0:
                info = pdata['imageinfo'][0]
                return info.get('thumburl') or info.get('url')
    return None

def process_and_save(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    target_w, target_h = 1920, 1080

    for idx, item in enumerate(ITEMS):
        base_name = f"{idx:02d}"
        jpg_path = os.path.join(out_dir, f"{base_name}.jpg")
        txt_path = os.path.join(out_dir, f"{base_name}.txt")

        print(f"[{idx+1}/{len(ITEMS)}] Mengambil {item['title']} ({item['file']})...")
        url = fetch_image_url(item['file'])
        if not url:
            print(f"  Gagal dapatkan URL untuk {item['file']}")
            continue

        print(f"  Downloading from {url}...")
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw_bytes = resp.read()

        img = Image.open(io.BytesIO(raw_bytes)).convert('RGB')
        
        # Center-crop to 16:9 aspect ratio and resize to 1920x1080
        src_w, src_h = img.size
        src_ratio = src_w / src_h
        target_ratio = target_w / target_h

        if src_ratio > target_ratio:
            # Source is wider than 16:9
            new_w = int(src_h * target_ratio)
            left = (src_w - new_w) // 2
            box = (left, 0, left + new_w, src_h)
        else:
            # Source is taller than 16:9
            new_h = int(src_w / target_ratio)
            top = (src_h - new_h) // 2
            box = (0, top, src_w, top + new_h)

        img_cropped = img.crop(box)
        img_resized = img_cropped.resize((target_w, target_h), Image.Resampling.LANCZOS)

        # Save optimized JPEG (~200KB - 300KB)
        img_resized.save(jpg_path, 'JPEG', quality=85, optimize=True)
        file_size_kb = os.path.getsize(jpg_path) / 1024
        print(f"  Disimpan: {jpg_path} ({file_size_kb:.1f} KB)")

        # Write metadata .txt (Line 1: Title, Line 2: Description, Line 3: Country)
        with open(txt_path, 'w', encoding='utf-8') as f:
            f.write(f"{item['title']}\n{item['desc']}\n{item['country']}\n")

    print("Semua latar belakang Islamik siap diproses!")

if __name__ == '__main__':
    import sys
    dest = sys.argv[1] if len(sys.argv) > 1 else 'islamic-wallpapers'
    process_and_save(dest)
