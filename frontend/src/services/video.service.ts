import api from './api.service';
import { Resume, ResumeAnalysis } from '../types';

// ============================================================
// Video / Resume API
// ============================================================

export const videoService = {
    async uploadResume(file: File): Promise<Resume> {
        const formData = new FormData();
        formData.append('file', file);

        const response = await api.post<Resume>('/api/resumes/upload', formData, {
            headers: { 'Content-Type': 'multipart/form-data' },
            timeout: 60000,
        });
        return response.data;
    },

    async getMyResume(): Promise<Resume> {
        const response = await api.get<Resume>('/api/resumes/my-resume');
        return response.data;
    },

    async getResumeById(id: number): Promise<Resume> {
        const response = await api.get<Resume>(`/api/resumes/${id}`);
        return response.data;
    },

    async analyzeResume(): Promise<ResumeAnalysis> {
        const response = await api.get<ResumeAnalysis>('/api/resumes/analyze');
        return response.data;
    },

    /**
     * Get a supported MIME type for video recording.
     */
    getSupportedMimeType(): string {
        const types = [
            'video/webm;codecs=vp9',
            'video/webm;codecs=vp8',
            'video/webm',
            'video/mp4',
        ];

        for (const type of types) {
            if (typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(type)) {
                return type;
            }
        }

        return 'video/webm'; // fallback
    },
};

export default videoService;
