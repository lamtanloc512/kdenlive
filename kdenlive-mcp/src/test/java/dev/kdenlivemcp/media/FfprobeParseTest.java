package dev.kdenlivemcp.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kdenlivemcp.model.Fps;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfprobeParseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static MediaInfo parse(String json) throws Exception {
        return FfprobeService.parse(Path.of("/m/x.mp4"), JSON.readTree(json), 10);
    }

    @Test
    void avFileWithRotation() throws Exception {
        MediaInfo m = parse("""
                {"streams":[
                  {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"r_frame_rate":"30000/1001",
                   "avg_frame_rate":"30000/1001","side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]},
                  {"codec_type":"audio","codec_name":"aac","sample_rate":"48000","channels":2}],
                 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"12.400000"}}
                """);
        assertThat(m.kind()).isEqualTo(MediaKind.AV);
        assertThat(m.durationSeconds()).isEqualTo(12.4);
        assertThat(m.fps()).isEqualTo(new Fps(30000, 1001));
        assertThat(m.rotation()).isEqualTo(270);
        assertThat(m.displayWidth()).isEqualTo(1080);
        assertThat(m.describe()).isEqualTo("12.400s 1080x1920@29.97 video:h264 audio:aac 48kHz stereo");
        assertThat(m.warnings()).containsExactly("rotation metadata 270°: shown rotated as 1080x1920");
    }

    @Test
    void audioOnlyWithCoverArtIsAudio() throws Exception {
        MediaInfo m = parse("""
                {"streams":[
                  {"codec_type":"audio","codec_name":"mp3","sample_rate":"44100","channels":2},
                  {"codec_type":"video","codec_name":"mjpeg","width":500,"height":500,"disposition":{"attached_pic":1}}],
                 "format":{"format_name":"mp3","duration":"180.5"}}
                """);
        assertThat(m.kind()).isEqualTo(MediaKind.AUDIO);
        assertThat(m.describe()).isEqualTo("180.500s audio:mp3 44kHz stereo");
    }

    @Test
    void stillImage() throws Exception {
        MediaInfo m = parse("""
                {"streams":[{"codec_type":"video","codec_name":"png","width":800,"height":600,"r_frame_rate":"25/1",
                  "avg_frame_rate":"0/0"}],
                 "format":{"format_name":"png_pipe"}}
                """);
        assertThat(m.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(m.durationSeconds()).isZero();
    }

    @Test
    void variableFrameRateIsFlagged() throws Exception {
        MediaInfo m = parse("""
                {"streams":[{"codec_type":"video","codec_name":"h264","width":1280,"height":720,"r_frame_rate":"60/1",
                  "avg_frame_rate":"2997/100"}],
                 "format":{"format_name":"mov,mp4","duration":"3.0"}}
                """);
        assertThat(m.variableFrameRate()).isTrue();
        assertThat(m.warnings()).hasSize(1);
    }

    @Test
    void noStreams() {
        assertThatThrownBy(() -> parse("{\"streams\":[],\"format\":{}}"))
                .hasMessage("x.mp4 has no audio or video stream");
    }
}
