import { AxiosResponse } from "axios";
import { API } from "./api";
import { friendResponse } from "../types/authType";

export const getFriendList = async () => {
  try {
    const res = await API.get<friendResponse[]>("/friend/all");
    return res.data;
  } catch (error) {
    console.error("친구 목록 조회 오류 : ", error);
    return null;
  }
};

export const getWaitingList = async () => {
  try {
    const res = await API.get<friendResponse[]>("/friend/request/all");
    return res.data;
  } catch (error) {
    console.error("대기중 목록 조회 오류 : ", error);
    return null;
  }
};

export const deleteFriend = async (friendId: number) => {
  try {
    const res: AxiosResponse<{ message: string }> = await API.delete(
      `/friend/${friendId}`
    );
    alert("친구가 해제되었습니다");
    return res.data.message;
  } catch (error) {
    console.error("친구 끊기 오류 : ", error);
    return null;
  }
};

export const confirmFriend = async (friendId: number, nickname: string) => {
  try {
    const res: AxiosResponse<{ message: string }> = await API.put(
      `/friend/request/${friendId}`
    );
    alert(`${nickname}님과 친구가 되었습니다`);
    return res.data.message;
  } catch (error) {
    console.error("친구 승낙 오류 : ", error);
    return null;
  }
};

export const denyFriend = async (friendId: number, nickname: string) => {
  try {
    const res: AxiosResponse<{ message: string }> = await API.delete(
      `/friend/request/${friendId}`
    );
    alert(`${nickname}님의 친구 신청이 거절되었습니다`);
    return res.data.message;
  } catch (error) {
    console.error("친구 거절 오류 : ", error);
    return null;
  }
};

export const registFriend = async (toId: number, nickname: string) => {
  try {
    const res: AxiosResponse<{ message: string }> = await API.post(
      `/friend/request/${toId}`
    );
    alert(`${nickname}님에게 친구를 신청했습니다`);
    return res.data.message;
  } catch (error) {
    console.error("친구 신청 오류 : ", error);
    return null;
  }
};
