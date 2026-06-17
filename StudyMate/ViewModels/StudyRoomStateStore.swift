import Foundation

struct StudyRoomStateStore {
    private(set) var rooms: [BackendStudyRoom] = []

    var hasRooms: Bool {
        !rooms.isEmpty
    }

    var pendingQuestionCount: Int {
        rooms.filter { Self.isPendingQuestion($0.pendingQuestion) }.count
    }

    mutating func replace(with rooms: [BackendStudyRoom]) {
        self.rooms = rooms
    }

    func pendingQuestionCount(for category: StudyCategory) -> Int? {
        let categoryKey = Self.normalizedText(category.title)
        let matchingRooms = rooms.filter { Self.normalizedText($0.topic) == categoryKey }

        guard !matchingRooms.isEmpty else {
            return nil
        }

        return matchingRooms.filter { Self.isPendingQuestion($0.pendingQuestion) }.count
    }

    func room(categoryID: String?, settings: StudySettings) -> BackendStudyRoom? {
        if let categoryID,
           let studyID = Int(categoryID),
           let room = rooms.first(where: { $0.id == studyID }) {
            return room
        }

        if let categoryID,
           let category = settings.category(for: categoryID),
           let room = rooms.first(where: {
               Self.normalizedText($0.topic) == Self.normalizedText(category.title)
           }) {
            return room
        }

        if let selectedCategoryID = settings.selectedStudyCategoryID,
           let studyID = Int(selectedCategoryID),
           let room = rooms.first(where: { $0.id == studyID }) {
            return room
        }

        return rooms.first
    }

    mutating func refreshPendingQuestions(from records: [StudyRecord]) {
        rooms = rooms.map { room in
            guard let pendingQuestion = room.pendingQuestion,
                  let refreshedRecord = records.first(where: { $0.id == pendingQuestion.id }) else {
                return room
            }

            var nextRoom = room
            nextRoom.pendingQuestion = refreshedRecord
            return nextRoom
        }
    }

    mutating func setPendingQuestion(_ record: StudyRecord, forStudyID studyID: Int) {
        rooms = rooms.map { room in
            guard room.id == studyID else {
                return room
            }

            var nextRoom = room
            nextRoom.pendingQuestion = record
            return nextRoom
        }
    }

    mutating func applyAnsweredRecord(_ record: StudyRecord) {
        rooms = rooms.map { room in
            guard room.pendingQuestion?.id == record.id else {
                return room
            }

            var nextRoom = room
            nextRoom.pendingQuestion = record
            return nextRoom
        }
    }

    mutating func clearPendingQuestion(recordID: String) {
        rooms = rooms.map { room in
            guard room.pendingQuestion?.id == recordID else {
                return room
            }

            var nextRoom = room
            nextRoom.pendingQuestion = nil
            return nextRoom
        }
    }

    private static func isPendingQuestion(_ record: StudyRecord?) -> Bool {
        guard let record else {
            return false
        }

        return record.gradingResult == nil
    }

    private static func normalizedText(_ text: String) -> String {
        text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined()
    }
}
