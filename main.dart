import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const MethodChannel _ch = MethodChannel('sarj/native');

const Color kBg = Color(0xFF0B1020);
const Color kCard = Color(0xFF151B30);
const Color kTrack = Color(0xFF222A45);
const Color kAccent = Color(0xFF3DDC97);
const Color kAmber = Color(0xFFFFC857);
const Color kRed = Color(0xFFFF5C6C);
const Color kMuted = Color(0xFF8B94B3);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    systemNavigationBarColor: kBg,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarIconBrightness: Brightness.light,
  ));
  runApp(const SarjApp());
}

class SarjApp extends StatelessWidget {
  const SarjApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Şarj Alarmı',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        useMaterial3: true,
        scaffoldBackgroundColor: kBg,
        colorScheme: const ColorScheme.dark(
          primary: kAccent,
          secondary: kAmber,
          surface: kCard,
        ),
        sliderTheme: const SliderThemeData(
          trackHeight: 6,
          activeTrackColor: kAccent,
          inactiveTrackColor: kTrack,
          thumbColor: kAccent,
          overlayColor: Color(0x293DDC97),
        ),
        switchTheme: SwitchThemeData(
          thumbColor: WidgetStateProperty.resolveWith(
              (s) => s.contains(WidgetState.selected) ? kBg : kMuted),
          trackColor: WidgetStateProperty.resolveWith(
              (s) => s.contains(WidgetState.selected) ? kAccent : kTrack),
          trackOutlineColor: WidgetStateProperty.all(Colors.transparent),
        ),
      ),
      home: const HomePage(),
    );
  }
}

class St {
  int level = -1;
  bool plugged = false;
  bool enabled = true;
  int threshold = 80;
  int volume = 100;
  bool vibrate = true;
  int duration = 3;
  bool stopOnUnplug = true;
  bool ringing = false;
  bool running = false;
  bool notifOk = true;
  bool exempt = false;
  String maker = '';

  void apply(Map<String, dynamic> m, {bool keepSliders = false}) {
    level = (m['level'] as int?) ?? level;
    plugged = (m['plugged'] as bool?) ?? plugged;
    enabled = (m['enabled'] as bool?) ?? enabled;
    if (!keepSliders) {
      threshold = (m['threshold'] as int?) ?? threshold;
      volume = (m['volume'] as int?) ?? volume;
    }
    vibrate = (m['vibrate'] as bool?) ?? vibrate;
    duration = (m['duration'] as int?) ?? duration;
    stopOnUnplug = (m['stopOnUnplug'] as bool?) ?? stopOnUnplug;
    ringing = (m['ringing'] as bool?) ?? ringing;
    running = (m['running'] as bool?) ?? running;
    notifOk = (m['notifOk'] as bool?) ?? notifOk;
    exempt = (m['exempt'] as bool?) ?? exempt;
    maker = (m['maker'] as String?) ?? maker;
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final St s = St();
  Timer? _timer;
  bool _dragging = false;
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final m = await _ch.invokeMapMethod<String, dynamic>('getState');
      if (m == null || !mounted) return;
      setState(() {
        s.apply(m, keepSliders: _dragging);
        _loaded = true;
      });
    } catch (_) {}
  }

  Future<void> _set(String key, Object value) async {
    try {
      await _ch.invokeMethod('set', {'key': key, 'value': value});
    } catch (_) {}
  }

  Future<void> _call(String name, [Object? args]) async {
    try {
      await _ch.invokeMethod(name, args);
    } catch (_) {}
    _refresh();
  }

  Color get _levelColor {
    if (s.level >= 0 && s.level <= 15) return kRed;
    if (s.level >= s.threshold) return kAmber;
    return kAccent;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: !_loaded
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: const EdgeInsets.fromLTRB(18, 14, 18, 28),
                children: [
                  _header(),
                  const SizedBox(height: 14),
                  if (s.ringing) _ringingBanner(),
                  _gaugeCard(),
                  const SizedBox(height: 14),
                  _mainSwitch(),
                  const SizedBox(height: 14),
                  _thresholdCard(),
                  const SizedBox(height: 14),
                  _optionsCard(),
                  const SizedBox(height: 14),
                  _healthCard(),
                ],
              ),
      ),
    );
  }

  Widget _header() {
    return Row(
      children: [
        Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: kAccent.withOpacity(0.15),
            borderRadius: BorderRadius.circular(14),
          ),
          child: const Icon(Icons.bolt_rounded, color: kAccent, size: 28),
        ),
        const SizedBox(width: 12),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Şarj Alarmı',
                  style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
              Text('Şarj dolunca sesli uyarı verir',
                  style: TextStyle(fontSize: 13, color: kMuted)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _ringingBanner() {
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: kRed.withOpacity(0.15),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: kRed.withOpacity(0.6)),
      ),
      child: Column(
        children: [
          const Row(
            children: [
              Icon(Icons.notifications_active_rounded, color: kRed, size: 28),
              SizedBox(width: 12),
              Expanded(
                child: Text('Alarm çalıyor',
                    style:
                        TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ),
            ],
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            height: 52,
            child: FilledButton.icon(
              style: FilledButton.styleFrom(
                backgroundColor: kRed,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16)),
              ),
              onPressed: () => _call('stopAlarm'),
              icon: const Icon(Icons.stop_rounded),
              label: const Text('ALARMI DURDUR',
                  style: TextStyle(fontWeight: FontWeight.w800)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _gaugeCard() {
    final lvl = s.level < 0 ? 0 : s.level;
    final status = s.plugged ? 'Şarj oluyor' : 'Şarjda değil';
    return _card(
      child: Column(
        children: [
          SizedBox(
            width: 250,
            height: 250,
            child: TweenAnimationBuilder<double>(
              tween: Tween<double>(end: lvl.toDouble()),
              duration: const Duration(milliseconds: 600),
              builder: (context, v, _) {
                return CustomPaint(
                  painter: GaugePainter(
                    level: v,
                    threshold: s.threshold.toDouble(),
                    color: _levelColor,
                  ),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(s.level < 0 ? '--' : '%${s.level}',
                            style: const TextStyle(
                                fontSize: 58, fontWeight: FontWeight.w800)),
                        const SizedBox(height: 2),
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                                s.plugged
                                    ? Icons.bolt_rounded
                                    : Icons.battery_std_rounded,
                                size: 18,
                                color: s.plugged ? kAccent : kMuted),
                            const SizedBox(width: 4),
                            Text(status,
                                style: TextStyle(
                                    fontSize: 14,
                                    color: s.plugged ? kAccent : kMuted)),
                          ],
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 4),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(width: 14, height: 4, color: kAmber),
              const SizedBox(width: 8),
              Text('Alarm %${s.threshold}\'de çalar',
                  style: const TextStyle(color: kMuted, fontSize: 13)),
            ],
          ),
        ],
      ),
    );
  }

  Widget _mainSwitch() {
    final ok = s.enabled && s.running;
    String sub;
    if (!s.enabled) {
      sub = 'Kapalı — alarm çalmaz';
    } else if (s.running) {
      sub = 'Aktif — arka planda sürekli çalışıyor';
    } else {
      sub = 'Başlatılıyor…';
    }
    return _card(
      child: Row(
        children: [
          Icon(ok ? Icons.shield_rounded : Icons.shield_outlined,
              color: ok ? kAccent : kMuted, size: 30),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Şarj alarmı',
                    style:
                        TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(sub, style: const TextStyle(color: kMuted, fontSize: 13)),
              ],
            ),
          ),
          Switch(
            value: s.enabled,
            onChanged: (v) {
              setState(() => s.enabled = v);
              _call('setEnabled', {'value': v});
            },
          ),
        ],
      ),
    );
  }

  Widget _thresholdCard() {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text('Kaç yüzdede çalsın?',
                    style:
                        TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
              ),
              Text('%${s.threshold}',
                  style: const TextStyle(
                      fontSize: 24,
                      fontWeight: FontWeight.w800,
                      color: kAmber)),
            ],
          ),
          Slider(
            value: s.threshold.toDouble().clamp(20.0, 100.0).toDouble(),
            min: 20,
            max: 100,
            divisions: 80,
            onChangeStart: (_) => _dragging = true,
            onChanged: (v) => setState(() => s.threshold = v.round()),
            onChangeEnd: (v) {
              _dragging = false;
              _set('threshold', v.round());
            },
          ),
          Wrap(
            spacing: 8,
            children: [60, 70, 80, 90, 100].map((p) {
              return ChoiceChip(
                label: Text('%$p'),
                selected: s.threshold == p,
                selectedColor: kAmber.withOpacity(0.25),
                onSelected: (_) {
                  setState(() => s.threshold = p);
                  _set('threshold', p);
                },
              );
            }).toList(),
          ),
        ],
      ),
    );
  }

  Widget _optionsCard() {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Alarm ayarları',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Row(
            children: [
              const Icon(Icons.volume_up_rounded, color: kMuted),
              Expanded(
                child: Slider(
                  value: s.volume.toDouble().clamp(10.0, 100.0).toDouble(),
                  min: 10,
                  max: 100,
                  divisions: 18,
                  onChangeStart: (_) => _dragging = true,
                  onChanged: (v) => setState(() => s.volume = v.round()),
                  onChangeEnd: (v) {
                    _dragging = false;
                    _set('volume', v.round());
                  },
                ),
              ),
              SizedBox(
                width: 44,
                child: Text('%${s.volume}',
                    textAlign: TextAlign.right,
                    style: const TextStyle(color: kMuted)),
              ),
            ],
          ),
          const Text(
              'Alarm sesi, telefon sessizde olsa bile çalar.',
              style: TextStyle(color: kMuted, fontSize: 12)),
          const SizedBox(height: 14),
          const Text('Çalma süresi',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              [1, '1 dk'],
              [3, '3 dk'],
              [5, '5 dk'],
              [0, 'Ben durdurana kadar'],
            ].map((e) {
              final v = e[0] as int;
              return ChoiceChip(
                label: Text(e[1] as String),
                selected: s.duration == v,
                onSelected: (_) {
                  setState(() => s.duration = v);
                  _set('duration', v);
                },
              );
            }).toList(),
          ),
          const SizedBox(height: 6),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Titreşim'),
            value: s.vibrate,
            onChanged: (v) {
              setState(() => s.vibrate = v);
              _set('vibrate', v);
            },
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Şarjı çekince alarmı sustur'),
            value: s.stopOnUnplug,
            onChanged: (v) {
              setState(() => s.stopOnUnplug = v);
              _set('stopOnUnplug', v);
            },
          ),
          const SizedBox(height: 6),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: OutlinedButton.icon(
              style: OutlinedButton.styleFrom(
                foregroundColor: kAccent,
                side: const BorderSide(color: kTrack),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14)),
              ),
              onPressed: () => _call('preview'),
              icon: const Icon(Icons.play_arrow_rounded),
              label: const Text('Alarmı dene'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _healthCard() {
    final xiaomi = s.maker.contains('xiaomi') ||
        s.maker.contains('redmi') ||
        s.maker.contains('poco');
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Kararlı çalışması için',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          const Text(
              'Telefonlar arka plandaki uygulamaları kapatabilir. Aşağıdakilerin hepsini bir kez ayarlamanız yeterli.',
              style: TextStyle(color: kMuted, fontSize: 12)),
          const SizedBox(height: 10),
          _checkRow('Bildirim izni', s.notifOk, 'İzin ver',
              () => _call('requestNotif')),
          _checkRow('Pil kısıtlaması yok', s.exempt, 'Kaldır',
              () => _call('requestBattery')),
          _checkRow(
              xiaomi ? 'Otomatik başlatma (Xiaomi)' : 'Arka planda çalışma',
              null,
              'Aç',
              () => _call('openAutostart')),
          if (xiaomi)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text(
                  'Xiaomi: "Otomatik başlatma"yı açın. Ayrıca Son uygulamalar ekranında bu uygulamanın kartını basılı tutup kilitleyin; pil tasarrufunu "Kısıtlama yok" yapın.',
                  style: TextStyle(color: kMuted, fontSize: 12)),
            ),
        ],
      ),
    );
  }

  Widget _checkRow(String text, bool? ok, String btn, VoidCallback onTap) {
    Widget lead;
    if (ok == null) {
      lead = const Icon(Icons.settings_rounded, color: kMuted);
    } else if (ok) {
      lead = const Icon(Icons.check_circle_rounded, color: kAccent);
    } else {
      lead = const Icon(Icons.error_rounded, color: kAmber);
    }
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          lead,
          const SizedBox(width: 12),
          Expanded(child: Text(text)),
          if (ok != true)
            TextButton(onPressed: onTap, child: Text(btn)),
        ],
      ),
    );
  }

  Widget _card({required Widget child}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: kCard,
        borderRadius: BorderRadius.circular(22),
      ),
      child: child,
    );
  }
}

class GaugePainter extends CustomPainter {
  final double level;
  final double threshold;
  final Color color;

  GaugePainter(
      {required this.level, required this.threshold, required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final r = size.width / 2 - 24;
    final rect = Rect.fromCircle(center: c, radius: r);
    const start = 135 * math.pi / 180;
    const sweep = 270 * math.pi / 180;

    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 18
      ..strokeCap = StrokeCap.round
      ..color = kTrack;
    canvas.drawArc(rect, start, sweep, false, track);

    final frac = (level / 100.0).clamp(0.0, 1.0).toDouble();
    if (frac > 0.002) {
      final prog = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 18
        ..strokeCap = StrokeCap.round
        ..color = color;
      canvas.drawArc(rect, start, sweep * frac, false, prog);
    }

    final a = start + sweep * (threshold / 100.0).clamp(0.0, 1.0).toDouble();
    final dir = Offset(math.cos(a), math.sin(a));
    final tick = Paint()
      ..color = kAmber
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(c + dir * (r - 17), c + dir * (r + 17), tick);
  }

  @override
  bool shouldRepaint(covariant GaugePainter old) =>
      old.level != level || old.threshold != threshold || old.color != color;
}
