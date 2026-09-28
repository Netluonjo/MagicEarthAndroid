# 🍏 Apple CarPlay & Apple Watch Integration Architecture Guide

Tài liệu thiết kế kiến trúc chuẩn để triển khai **Apple CarPlay** và **Apple Watch App** cho hệ sinh thái Magic Earth trên nền tảng iOS (Swift / Xcode).

---

## 1. 🚗 Apple CarPlay Integration (`CarPlay.framework`)

Trên iOS, Apple kiểm soát giao diện trên màn hình xe hơi thông qua `CarPlay.framework` nhằm đảm bảo an toàn giao thông và tiêu chuẩn thiết kế của Apple.

### 1.1. Cấu hình Entitlements (`CarPlay.entitlements`)
Ứng dụng Navigation cần xin phép Apple quyền (entitlement):
```xml
<key>com.apple.developer.carplay-maps</key>
<true/>
```

### 1.2. Scene Delegate cho Xe Hơi (`CarPlaySceneDelegate.swift`)
Quản lý vòng đời khi cắm iPhone vào màn hình trung tâm của ô tô:
```swift
import CarPlay

class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    var interfaceController: CPInterfaceController?
    var mapTemplate: CPMapTemplate?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        
        // 1. Tạo Map Template hiển thị bản đồ toàn màn hình
        let mapTemplate = CPMapTemplate()
        mapTemplate.mapDelegate = self
        
        // 2. Action Strip (Nút Mute âm thanh, Dừng dẫn đường)
        let muteButton = CPBarButton(type: .image) { [weak self] _ in
            VoiceGuidanceManager.shared.toggleMute()
        }
        muteButton.image = UIImage(systemName: "speaker.wave.2.fill")
        mapTemplate.trailingNavigationBarButtons = [muteButton]
        
        self.mapTemplate = mapTemplate
        interfaceController.setRootTemplate(mapTemplate, animated: true, completion: nil)
    }
}
```

### 1.3. Hiển thị Chỉ dẫn từng bước (Turn-by-Turn Maneuvers)
Khi người dùng bắt đầu hành trình:
```swift
func startCarPlayNavigation(route: Route) {
    let trip = CPTrip(origin: route.originItem, destination: route.destItem, routeChoices: [route.choice])
    let navSession = mapTemplate?.startNavigationSession(for: trip)
    
    // Cập nhật ngã rẽ và khoảng cách
    let step = CPManeuver()
    step.instructionVariants = ["Rẽ phải vào Nguyễn Trãi sau 300 mét"]
    step.symbolImage = UIImage(systemName: "arrow.turn.up.right")
    
    navSession?.upcomingManeuvers = [step]
}
```

---

## 2. ⌚ Apple Watch App (`WatchKit` & `WatchConnectivity`)

Apple Watch kết nối trực tiếp với iPhone qua giao thức `WatchConnectivity (WCSession)` để đồng bộ chỉ dẫn và rung xúc giác (haptic feedback).

### 2.1. Đồng bộ Dữ liệu Telemetry qua `WCSession`
Trên iPhone:
```swift
import WatchConnectivity

class WatchSyncManager: NSObject, WCSessionDelegate {
    static let shared = WatchSyncManager()
    
    func broadcastTurnUpdate(icon: String, instruction: String, distanceMeters: Int, speed: Int, limit: Int) {
        guard WCSession.default.isReachable else { return }
        let payload: [String: Any] = [
            "icon": icon,
            "instruction": instruction,
            "dist": distanceMeters,
            "speed": speed,
            "limit": limit
        ]
        WCSession.default.sendMessage(payload, replyHandler: nil)
    }
}
```

### 2.2. Giao diện Mặt Đồng Hồ (SwiftUI for watchOS)
```swift
import SwiftUI
import WatchKit

struct WatchNavigationDashboardView: View {
    @ObservedObject var telemetry: WatchTelemetryViewModel

    var body: some View {
        VStack(spacing: 6) {
            // Icon rẽ lớn & Khoảng cách đếm lùi
            HStack {
                Text(telemetry.turnIcon)
                    .font(.system(size: 38, weight: .bold))
                    .foregroundColor(.yellow)
                Spacer()
                Text("\(telemetry.distanceMeters) m")
                    .font(.system(size: 26, weight: .heavy))
                    .foregroundColor(.white)
            }
            
            // Hướng dẫn rẽ
            Text(telemetry.instruction)
                .font(.footnote)
                .lineLimit(2)
                .foregroundColor(.gray)
            
            Divider()
            
            // Tốc độ hiện tại & Giới hạn
            HStack {
                Text("⚡ \(telemetry.speed) km/h")
                    .font(.caption)
                    .foregroundColor(telemetry.isOverspeed ? .red : .green)
                Spacer()
                Text("Tối đa: \(telemetry.speedLimit)")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .onChange(of: telemetry.distanceMeters) { dist in
            // Rung cổ tay nhẹ khi cách điểm rẽ 50m
            if dist <= 50 {
                WKInterfaceDevice.current().play(.directionUp)
            }
        }
    }
}
```

---

## 3. 🔄 Sơ Đồ Đồng Bộ Đa Nền Tảng (Cross-Platform Architecture)

```
[ Navigation Engine / GPS Telemetry ]
                 │
                 ├──► Mobile In-App View (MainActivity / HUDActivity)
                 │
                 ├──► Android Auto (androidx.car.app / NavigationTemplate)
                 │
                 ├──► Smartwatch (Wearable Navigation / Haptic Wrist Pulse)
                 │
                 └──► [Tương lai iOS] Apple CarPlay & Apple Watch (CarPlay.framework + WatchKit)
```
