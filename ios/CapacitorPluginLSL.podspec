require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name = 'CapacitorPluginLSL'
  s.version = package['version']
  s.summary = 'Capacitor plugin for Lab Streaming Layer (LSL)'
  s.license = 'MIT'
  s.homepage = 'https://github.com/mindfield-biosystems/capacitor-lsl'
  s.author = 'Mindfield Biosystems Ltd.'
  s.source = { git: 'https://github.com/mindfield-biosystems/capacitor-lsl.git', tag: s.version.to_s }
  s.source_files = 'Plugin/**/*.{swift,h,m,c,cc,mm}'
  s.ios.deployment_target = '14.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'

  # Pre-built liblsl framework
  s.vendored_frameworks = 'liblsl.xcframework'

  # LSL uses UDP multicast for stream discovery
  s.frameworks = 'Foundation'
end
