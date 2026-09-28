# Magic Earth Navigation & Maps - Android Studio Project

Ứng dụng bản đồ và dẫn đường vệ tinh tập trung vào **Bảo mật dữ liệu cá nhân (100% On-Device Privacy - Không theo dõi, không thu thập dữ liệu)**, sử dụng dữ liệu nguồn mở **OpenStreetMap (OSM)**, dựng mô hình 3D và hỗ trợ toàn diện cả Online lẫn Offline.

---

## 🚀 Hướng Dẫn Mở & Chạy Trên Android Studio

### Yêu Cầu Môi Trường
- **Android Studio**: Android Studio Koala / Ladybug / 2026.x (đã cài tại `C:\andrioid`).
- **Android SDK**: API 34 hoặc 35 (`C:\Users\asus\AppData\Local\Android\Sdk`).
- **JDK**: Java 17+ (tích hợp sẵn trong Android Studio JBR).

### Các Bước Mở Dự Án:
1. Khởi động **Android Studio**.
2. Chọn **File -> Open...** (hoặc **Open** tại màn hình Welcome).
3. Điều hướng và chọn thư mục:  
   `C:\Users\asus\.gemini\antigravity-ide\scratch\MagicEarthAndroid`
4. Chờ Android Studio tự động đồng bộ Gradle (**Gradle Sync**). Dự án đã tích hợp sẵn:
   - `local.properties`: Cấu hình sẵn đường dẫn Android SDK của máy bạn.
   - `gradlew.bat` & `gradle-wrapper.jar`: Bản Gradle 8.9 chuẩn đã cache trên máy.
5. Chọn thiết bị chạy:
   - **Máy ảo (Android Emulator / AVD)**: Pixel 7/8 chạy Android 14/15.
   - **Hoặc điện thoại thật**: Cắm cáp USB và bật chế độ *Gỡ lỗi USB (USB Debugging)*.
6. Nhấn nút **Run 'app'** (phím tắt `Shift + F10`) để build và khởi chạy ứng dụng!

---

## 🌟 Chi Tiết 7 Phân Hệ Cốt Lõi (Architecture & Implementation)

### 1. Bản Đồ & Hiển Thị (Map Rendering & Engine)
- **Engine**: MapLibre GL Native Android SDK (`org.maplibre.gl:android-sdk:11.0.0`).
- **2D & 3D Extrusions**: Nút **3D FAB** tự động nghiêng camera $55^\circ$ và xoay góc bearing $-20^\circ$ để hiển thị khối nhà 3D.
- **Đa lớp hiển thị (Layer Selector)**: Nút **Layers FAB** cho phép chuyển đổi tức thì giữa:
  - 🗺️ *Bản đồ Vector 3D (OSM)*
  - 🛰️ *Ảnh Vệ Tinh Trực Quan (Satellite)*
  - ⛰️ *Bản đồ Địa Hình & Đổ Bóng Cao Độ (Terrain)*
  - 🌙 *Chế độ Đêm OLED Tiết Kiệm Pin (Dark)*
- **Offline Maps**: Quản lý gói tải ngoại tuyến (Việt Nam 1.15 GB, Pháp 5.9 GB, Đức 3.2 GB) và kiểm tra cập nhật bản vá OSM.
- **File phụ trách**: `map/MapEngineManager.kt`, `map/OfflineMapManager.kt`.

### 2. Định Vị, Tìm Kiếm & POI (Search & Geocoding)
- **Offline Search Engine**: CSDL cục bộ `PoiRepository.kt` tìm kiếm tên đường, số nhà và các điểm quan tâm:
  - ⛽ Cây xăng Petrolimex, PVOIL
  - ⚡ Trạm sạc xe điện siêu nhanh VinFast Green Charge
  - 🅿️ Bãi đỗ xe thông minh & tầng ngầm
  - 🏛️ Danh lam thắng cảnh & di tích văn hóa
- **Tích hợp Wikipedia**: Khi chọn các di tích lịch sử (Hồ Hoàn Kiếm, Văn Miếu, Hoàng Thành Thăng Long), ứng dụng hiển thị **BottomSheet văn hóa** tóm tắt bài viết Wikipedia, ảnh minh họa và nút *Dẫn đường tới đây*.
- **Favourites / Saved Places**: Quản lý ghim điểm yêu thích, Nhà riêng (Home), Cơ quan (Work).
- **File phụ trách**: `map/PoiRepository.kt`, `ui/PoiAdapter.kt`, `bottom_sheet_wiki.xml`.

### 3. Thuật Toán Dẫn Đường (Routing Engine)
- **Đa phương thức (Multi-modal)**:
  - 🚗 **Ô tô (Car)**: Tối ưu thời gian, cập nhật ùn tắc.
  - 🚚 **Xe tải (Truck)**: Hỗ trợ hộp thoại cấu hình **Tải trọng (tấn)**, **Chiều cao xe (m)**, **Số trục** nhằm tránh các cầu cấm tải, cầu vượt hạn chế tĩnh không và phố hẹp cấm xe tải.
  - 🚲 **Xe đạp / E-Bike**: Tính toán độ dốc (Slope calculation), ưu tiên đường ven hồ, đường công viên.
  - 🚶 **Đi bộ (Pedestrian)**: Tìm lối tắt, đường mòn vỉa hè bộ hành.
  - 🚌 **Giao thông công cộng (Transit)**: Kết hợp Metro Cát Linh - Hà Đông, Nhổn - Ga Hà Nội và xe buýt, thông báo số trạm và hỗ trợ xe lăn.
- **Lộ trình nhiều điểm dừng (Waypoints)**: Hỗ trợ bấm *+ Thêm điểm dừng C* (ví dụ: dừng tại Cầu Nhật Tân trước khi tới Nội Bài) và tự động tính toán lại lộ trình tối ưu.
- **Elevation Profile & Bề mặt đường**: Custom View `ElevationProfileView.kt` vẽ biểu đồ trắc dọc cao độ trực tiếp bằng Canvas phần cứng, hiển thị tỷ lệ bề mặt (94% Nhựa, 6% Bê tông).
- **File phụ trách**: `routing/RoutingEngine.kt`, `ui/ElevationProfileView.kt`, `dialog_truck_config.xml`.

### 4. Dẫn Đường Turn-by-Turn & Chế Độ HUD
- **Chỉ dẫn giọng nói (Voice Guidance)**: Tích hợp `VoiceGuidanceManager.kt` sử dụng Android Native `TextToSpeech` phát giọng đọc tự nhiên bằng tiếng Việt thông báo khoảng cách và tên đường rẽ.
- **Hỗ trợ làn đường (Lane Assistance)**: Custom View `LaneAssistanceView.kt` hiển thị trực quan các làn xe phía trước, đánh dấu mũi tên màu xanh an toàn cho làn tài xế cần chuyển vào.
- **Bảng chỉ dẫn đường bộ (Signpost)**: Mô phỏng biển báo cao tốc màu xanh lá `highway-green`, hiển thị mã tuyến `CT.01 / AH1` và lối ra `Lối ra 08 Sân Bay Nội Bài`.
- **Cảnh báo tốc độ & Camera**: Đồng hồ tốc độ thời gian thực, biển giới hạn tốc độ đổi màu đỏ khi vượt tốc độ, và cảnh báo âm thanh camera phạt nguội phía trước.
- **Head-Up Display (HUD Activity)**: Màn hình fullscreen đen tuyền OLED với hiệu ứng lật gương ngang `scaleX = -1f` để tài xế đặt điện thoại lên taplo xe hơi ban đêm, hình ảnh phản chiếu trên kính lái rõ nét không bị ngược chữ.
- **File phụ trách**: `navigation/NavigationSession.kt`, `navigation/VoiceGuidanceManager.kt`, `ui/LaneAssistanceView.kt`, `hud/HUDActivity.kt`.

### 5. Dịch Vụ Thời Gian Thực (Real-time Services)
- **Live Traffic**: Bật/tắt lớp hiển thị ùn tắc giao thông trực tiếp với dải màu Xanh/Vàng/Đỏ.
- **Cảnh báo thời tiết theo lộ trình**: Thẻ thông báo tự động xuất hiện cảnh báo mưa bão hoặc sương mù dày đặc cách 12km phía trước để tài xế chủ động giảm tốc độ.
- **Báo cáo sự cố cộng đồng**: Nút **FAB Report** mở hộp thoại 1 chạm để tài xế gửi cảnh báo:
  - 💥 Tai nạn giao thông
  - 📷 Camera / Bắn tốc độ
  - 🚧 Công trường thi công
  - ⚠️ Chướng ngại vật / Ngập úng
- **File phụ trách**: `dialog_incident_report.xml`, `MainActivity.kt`.

### 6. Ghi Log Hành Trình (GPX Trip Recording)
- **Foreground Service**: `GPXRecorderService.kt` chạy nền bền bỉ dưới hệ điều hành, không bị tắt khi khóa màn hình điện thoại.
- **Thu thập dữ liệu**: Lưu trữ vĩ độ, kinh độ, cao độ, thời gian, vận tốc tức thời và vận tốc trung bình.
- **Gắn Media & Ghi chú**: Đính kèm ghi chú dọc chặng đường.
- **Xuất file chuẩn GPX**: Tạo file `.gpx` chuẩn hóa vào thư mục ứng dụng để mở trên Google Earth hoặc chia sẻ qua Zalo, Messenger, Gmail.
- **File phụ trách**: `service/GPXRecorderService.kt`.

### 7. Tích Hợp Phần Cứng & Chuẩn Bị Mở Rộng
- Dự án cấu hình sẵn sàng cho **Android Auto** thông qua AndroidX Car App Library.
- Kiến trúc Clean Architecture phân tách rõ ràng giữa Model, View, Service, và Map Engine.

---

## 📂 Cấu Trúc Thư Mục Dự Án
```
MagicEarthAndroid/
├── app/
│   ├── build.gradle.kts           # Cấu hình SDK 34, ViewBinding, dependencies
│   ├── proguard-rules.pro         # Proguard rules cho MapLibre
│   └── src/main/
│       ├── AndroidManifest.xml    # Permissions, Activities, Services
│       ├── java/com/magicearth/navigation/
│       │   ├── MainActivity.kt               # Điều phối giao diện & 7 module
│       │   ├── hud/HUDActivity.kt            # Màn hình phản chiếu taplo kính lái
│       │   ├── map/
│       │   │   ├── MapEngineManager.kt       # Điều khiển camera 2D/3D & đa layer
│       │   │   ├── OfflineMapManager.kt      # Quản lý bản đồ ngoại tuyến
│       │   │   └── PoiRepository.kt          # CSDL POI cục bộ & Wikipedia
│       │   ├── model/Models.kt               # POI, Maneuver, RouteMetrics, Incident
│       │   ├── navigation/
│       │   │   ├── NavigationSession.kt      # Giả lập Turn-by-Turn & Camera alert
│       │   │   └── VoiceGuidanceManager.kt   # Trình phát TextToSpeech tiếng Việt
│       │   ├── routing/RoutingEngine.kt      # Đa phương thức, xe tải & waypoints
│       │   ├── service/GPXRecorderService.kt # Foreground service ghi log GPX
│       │   └── ui/
│       │       ├── ElevationProfileView.kt   # Custom View vẽ biểu đồ cao độ Canvas
│       │       ├── LaneAssistanceView.kt     # Custom View vẽ mũi tên làn đường
│       │       └── PoiAdapter.kt             # RecyclerView Adapter kết quả POI
│       └── res/
│           ├── layout/                       # Layout XML activity, dialog, bottom sheet
│           ├── values/                       # colors.xml, strings.xml, themes.xml
│           └── drawable/                     # Shape glass, biển báo cao tốc, logo
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties  # Gradle 8.9
├── build.gradle.kts               # Root project build file
├── settings.gradle.kts            # Project repositories & module include
├── local.properties               # Cấu hình SDK cục bộ máy tính
└── gradlew.bat                    # Gradle Wrapper command line script
```
