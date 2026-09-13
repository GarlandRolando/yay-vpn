import SwiftUI
struct WorldMap: View {
 let active: Bool
 private let land: [[Double]] = [
[7,24,13,16,21,12,28,16,32,23,29,31,25,33,24,42,20,46,16,39,12,38,9,31],
[21,44,25,47,29,49,32,57,30,66,27,76,24,82,23,71,20,61,20,52],
[31,9,38,7,39,17,35,25,31,20],
[43,27,47,20,51,19,52,25,57,29,53,35,48,34,46,39,42,34],
[45,38,53,37,59,44,58,54,55,64,51,70,47,59,46,49,42,44],
[53,23,58,18,67,17,72,12,81,17,92,21,93,29,86,33,83,39,79,42,76,48,73,44,70,37,66,41,63,49,59,42,59,33,55,31],
[74,48,77,52,80,54,79,57,75,54],
[81,59,88,56,93,64,91,71,83,73,78,68],
[94,73,96,69,96,76,93,80],
[60,60,61,66,59,70,58,65],
[86,37,88,32,89,39,87,43],
[77,58,83,59,86,62,81,62],
[5,89,16,86,33,88,46,85,61,88,77,85,93,88,96,94,6,94]
 ]
 var body: some View { Canvas { context, size in
  for polygon in land { var path = Path(); path.move(to: CGPoint(x: polygon[0] * size.width / 100, y: polygon[1] * size.height / 100)); for i in stride(from: 2, to: polygon.count, by: 2) { path.addLine(to: CGPoint(x: polygon[i] * size.width / 100, y: polygon[i+1] * size.height / 100)) }; path.closeSubpath(); context.fill(path, with: .color((active ? Color.green : Color.red).opacity(0.27))) }
 }.accessibilityHidden(true) }
}
