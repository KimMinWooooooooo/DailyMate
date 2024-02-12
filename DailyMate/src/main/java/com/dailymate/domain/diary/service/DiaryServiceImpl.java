package com.dailymate.domain.diary.service;

import com.dailymate.domain.diary.constant.Feeling;
import com.dailymate.domain.diary.constant.OpenType;
import com.dailymate.domain.diary.constant.Weather;
import com.dailymate.domain.diary.dao.DiaryRepository;
import com.dailymate.domain.diary.dao.LikeDiaryRepository;
import com.dailymate.domain.diary.domain.Diary;
import com.dailymate.domain.diary.domain.LikeDiary;
import com.dailymate.domain.diary.domain.LikeDiaryKey;
import com.dailymate.domain.diary.dto.DiaryMonthlyResDto;
import com.dailymate.domain.diary.dto.DiaryReqDto;
import com.dailymate.domain.diary.dto.DiaryResDto;
import com.dailymate.domain.diary.exception.DiaryBadRequestException;
import com.dailymate.domain.diary.exception.DiaryExceptionMessage;
import com.dailymate.domain.diary.exception.DiaryForbiddenException;
import com.dailymate.domain.diary.exception.DiaryNotFoundException;
import com.dailymate.domain.user.UserRepository;
import com.dailymate.domain.user.Users;
import com.dailymate.global.image.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiaryServiceImpl implements DiaryService {

    private final DiaryRepository diaryRepository;
    private final ImageService imageService;
    private final UserRepository userRepository;
    private final LikeDiaryRepository likeDiaryRepository;

    /**
     * 일기 작성
     * @param diaryReqDto DiaryReqDto
     * @param image MultipartFile
     */
    @Override
    @Transactional
    public void addDiary(DiaryReqDto diaryReqDto, MultipartFile image) {

        // 제목 입력값 검증
        if(!StringUtils.hasText(diaryReqDto.getTitle())) {
            throw new DiaryBadRequestException("[ADD_DIARY] " + DiaryExceptionMessage.DIARY_BAD_REQUEST.getMsg());
        }

        // 사용자 존재하는지 확인(!!!)

        // 해당 날짜에 일기가 존재하는지 확인(!!!)
        if(diaryRepository.existsDiaryByDateAndUserId(diaryReqDto.getDate(), diaryReqDto.getUserId())) {
            throw new DiaryBadRequestException("[ADD_DIARY] " + DiaryExceptionMessage.DIARY_ALREADY_EXIST.getMsg());
        }

        Diary diary = Diary.createDiary(diaryReqDto);

        // 이미지 등록
        if(image != null && !image.isEmpty()) {
            String imageUrl = imageService.uploadImage(image);
            diary.updateImage(imageUrl);
        }

        diaryRepository.save(diary);
    }

    /**
     * 일기 수정
     * @param diaryReqDto DiaryReqDto
     * @param image MultipartFile
     */
    @Override
    @Transactional
    public void updateDiary(Long diaryId, DiaryReqDto diaryReqDto, MultipartFile image) {

        // 일기 존재하는 지 확인
        Diary diary = diaryRepository.findById(diaryId)
                .orElseThrow(() -> {
                    throw new DiaryNotFoundException("[UPDATE_DIARY] " + DiaryExceptionMessage.DIARY_NOT_FOUND.getMsg());
                });

        // 이미 삭제된 일기인지 확인
        if(diary.getDeletedAt() != null) {
            throw new DiaryNotFoundException("[UPDATE_DIARY] " + DiaryExceptionMessage.DIARY_ALREADY_DELETED.getMsg());
        }

        // 일기 작성자와 같은지 확인(!!!)
        if(diary.getUserId() != diaryReqDto.getUserId()) {
            throw new DiaryForbiddenException("[UPDATE_DIARY] " + DiaryExceptionMessage.DIARY_HANDLE_ACCESS_DENIED.getMsg());
        }

        // 해당 날짜에 일기가 존재하는지 확인(!!!)
        if(diaryRepository.existsDiaryByDateAndUserId(diaryReqDto.getDate(), diaryReqDto.getUserId())) {
            throw new DiaryBadRequestException("[UPDATE_DIARY] " + DiaryExceptionMessage.DIARY_ALREADY_EXIST.getMsg());
        }

        // 일기 제목 입력값 확인
        if(!StringUtils.hasText(diaryReqDto.getTitle())) {
            throw new DiaryBadRequestException("[UPDATE_DIARY] " + DiaryExceptionMessage.DIARY_BAD_REQUEST.getMsg());
        }

        // 일기 수정
        diary.updateDiary(diaryReqDto.getTitle(),
                diaryReqDto.getContent(),
                diaryReqDto.getDate(),
                Weather.getWeather(diaryReqDto.getWeather()),
                Feeling.getFeeling(diaryReqDto.getFeeling()),
                OpenType.getOpenType(diaryReqDto.getOpenType()));

        // 이미지 입력값 확인
        if(image != null && !image.isEmpty()) {

            // 기존 이미지가 존재한다면 삭제
            if(diary.getImage() != null) {
                imageService.deleteImage(diary.getImage());
            }

            // 새로운 이미지 등록
            String imageUrl = imageService.uploadImage(image);
            diary.updateImage(imageUrl);
        }

    }

    /**
     * 일기 삭제
     * @param diaryId Long
     */
    @Override
    @Transactional
    public void deleteDiary(Long diaryId) {

        // 일기 존재하는 지 확인
        Diary diary = diaryRepository.findById(diaryId)
                .orElseThrow(() -> {
                    throw new DiaryNotFoundException("[DELETE_DIARY] " + DiaryExceptionMessage.DIARY_NOT_FOUND.getMsg());
                });

        // 이미 삭제된 일기인지 확인
        if(diary.getDeletedAt() != null) {
            throw new DiaryNotFoundException("[DELETE_DIARY] " + DiaryExceptionMessage.DIARY_ALREADY_DELETED.getMsg());
        }

        // 일기 작성자와 같은지 확인(!!!)

        // 이미지가 존재한다면 삭제
        if(diary.getImage() != null) {
            imageService.deleteImage(diary.getImage());
        }

        // 좋아요 삭제
        likeDiaryRepository.deleteAllByDiary(diary);

        // 일기 삭제
        diary.delete();
    }

    /**
     * 일기 좋아요
     * @param diaryId Long
     * @param userId Long
     */
    @Override
    @Transactional
    public void likeDiary(Long diaryId, Long userId) {

        // 일기 확인
        Diary diary = diaryRepository.findById(diaryId)
                .orElseThrow(() -> new DiaryNotFoundException("[LIKE_DIARY] " + DiaryExceptionMessage.DIARY_NOT_FOUND.getMsg()));

        // 사용자 확인(!!!)
        Users user = userRepository.findById(userId)
                .orElseThrow(() -> new DiaryNotFoundException("[LIKE_DIARY] " + DiaryExceptionMessage.USER_NOT_FOUND.getMsg()));

        // 친구 관계 확인(!!!)

        // 공개 여부 확인(!!!)

        // 복합키 생성
        LikeDiaryKey key = LikeDiaryKey.createKey(userId, diaryId);

        // 좋아요 토글
        Optional<LikeDiary> likeDiary = likeDiaryRepository.findById(key);

        if(likeDiary.isPresent()) {
            likeDiaryRepository.delete(likeDiary.get());
        } else {
            likeDiaryRepository.save(LikeDiary.builder()
                    .user(user)
                    .diary(diary)
                    .build());
        }
    }

    /**
     * 일기 조회 (일별)
     * @param date String
     * @param userId Long
     * @return DiaryResDto
     */
    @Override
    @Transactional
    public DiaryResDto findDiary(String date, Long userId) {

        // 사용자 확인(!!!)
        Users user = userRepository.findById(userId)
                .orElseThrow(() -> new DiaryNotFoundException("[FIND_DIARY] " + DiaryExceptionMessage.USER_NOT_FOUND.getMsg()));

        // 일기 확인
        Diary diary = diaryRepository.findDiaryByDateAndUserId(date, userId);

        // 일기 없으면 null 반환
        if(diary == null) {
            return null;
        }

        // 좋아요 여부
        Boolean isLike = false;

        LikeDiaryKey key = LikeDiaryKey.createKey(userId, diary.getDiaryId());
        Optional<LikeDiary> likeDiary = likeDiaryRepository.findById(key);

        if(likeDiary.isPresent()) {
            isLike = true;
        }

        // 좋아요 개수
        Long likeNum = likeDiaryRepository.countLikesByDiaryId(diary.getDiaryId());

        return DiaryResDto.createDto(diary, likeNum, isLike);
    }

    /**
     * 일기 조회 (월별)
     * @param date String
     * @param userId Long
     * @return DiaryMonthlyResDto[]
     */
    @Override
    public DiaryMonthlyResDto[] findDiaryByMonth(String date, Long userId) {

        // 입력값 검증
        if(date == null || date.isEmpty() || userId == null) {
            throw new DiaryBadRequestException("[FIND_DIARY_BY_MONTH] " + DiaryExceptionMessage.DIARY_BAD_REQUEST.getMsg());
        }

        // 사용자 확인(!!!)
        Users user = userRepository.findById(userId)
                .orElseThrow(() -> new DiaryNotFoundException("[FIND_DIARY_BY_MONTH] " + DiaryExceptionMessage.USER_NOT_FOUND.getMsg()));

        List<DiaryMonthlyResDto> diaries = diaryRepository.findByUserIdAndYearMonth(userId, date);

        DiaryMonthlyResDto[] monthly = new DiaryMonthlyResDto[32];

        // 인덱스에 해당하는 날짜의 일기 넣어주기
        for(DiaryMonthlyResDto diary : diaries) {
            int day = Integer.parseInt(diary.getDate().substring(8, 10));
            monthly[day] = diary;
        }

        return monthly;
    }

    /**
     * 친구 일기 조회 (일별)
     * @param diaryId Long
     * @param userId Long
     * @return DiaryResDto
     */
    @Override
    public DiaryResDto findFriendDiary(Long diaryId, Long userId) {

        // 입력값 검증
        if(diaryId == null || userId == null) {
            throw new DiaryBadRequestException("[FIND_FRIEND_DIARY] " + DiaryExceptionMessage.DIARY_BAD_REQUEST.getMsg());
        }

        // 사용자 확인(!!!)
        Users user = userRepository.findById(userId)
                .orElseThrow(() -> new DiaryNotFoundException("[FIND_DIARY_BY_MONTH] " + DiaryExceptionMessage.USER_NOT_FOUND.getMsg()));

        // 다이어리 확인
        Diary diary = diaryRepository.findById(diaryId)
                .orElseThrow(() -> new DiaryNotFoundException("[FIND_FRIEND_DIARY] " + DiaryExceptionMessage.DIARY_NOT_FOUND.getMsg()));

        // 열람 자격 확인(!!!)
        // diary userId랑 userId랑 친구 -> diary 비공개 아니면 ㅇㅋ -> else 는 예외 처리
        // diary userId랑 userId랑 친구 ㄴㄴ -> diary 공개만 ㅇㅋ -> else 는 예외 처리

        // 좋아요 여부
        Boolean isLike = false;

        LikeDiaryKey key = LikeDiaryKey.createKey(userId, diaryId);
        Optional<LikeDiary> likeDiary = likeDiaryRepository.findById(key);

        if(likeDiary.isPresent()) {
            isLike = true;
        }

        // 좋아요 개수
        Long likeNum = likeDiaryRepository.countLikesByDiaryId(diaryId);

        return DiaryResDto.createDto(diary, likeNum, isLike);

    }
}
