# CLAUDE.md

- Kullanıcıyla her zaman **Türkçe** konuş; teknik terimler hariç.
- Proje durumu, kararlar ve kalan işler için önce `DEVIR.md` dosyasını oku.
- `./gradlew :core:test` Android SDK olmadan çalışır; `:app` yalnızca SDK varsa (ANDROID_HOME ya da
  local.properties) projeye dahil edilir.
- İş mantığını mümkün olduğunca `core/` içine koy ve birim testi yaz; `app/` yalnızca Android'e özgü kod içersin.
