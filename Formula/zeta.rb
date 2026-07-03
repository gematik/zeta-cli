class Zeta < Formula
  desc "Swiss-army-knife Zeta client and tools"
  homepage "https://github.com/gematik/zeta-cli"
  url "https://github.com/gematik/zeta-cli/releases/download/v0.7.0/zeta-0.7.0.tar.gz"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    %w[zeta zeta-stress].each do |exe|
      (bin/exe).write_env_script libexec/"bin/#{exe}",
        Language::Java.overridable_java_home_env("21")
    end
  end

  test do
    assert_match(/^zeta /, shell_output("#{bin}/zeta version"))
  end
end
